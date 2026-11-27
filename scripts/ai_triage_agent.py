#!/usr/bin/env python3
"""
Agente IA de triaje automático de reportes de bugs y mejoras para EnduLink:
1. Lee los issues abiertos del repositorio para detectar duplicados.
2. Analiza el reporte + diagnóstico técnico + logs TFT + crash log usando:
   - GitHub Models API (gpt-4o con GITHUB_TOKEN)
   - Fallback a Gemini API (GEMINI_API_KEY si está configurada)
   - Fallback heurístico local por módulos si no hay cuota/API disponible
3. Si es duplicado de un issue abierto (#N), comenta en #N preservando el mensaje original del usuario.
   Si es nuevo, crea el Issue redactado con:
   - Resumen técnico mejorado por IA
   - Hipótesis de causa raíz y Plan de solución (archivos y pasos concretos)
   - Mensaje original íntegro del usuario
   - Diagnóstico, ubicación en mapa y logs adjuntos
4. Envía callback al Web App de Google Apps Script (si está configurado) para mandar el email a laitaodev@gmail.com.
"""

import difflib
import json
import os
import re
import urllib.error
import urllib.request

REPO = os.environ.get("GITHUB_REPOSITORY", "laita-dev/EnduLink")
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
SECRET_RELAY_URL = os.environ.get("BUG_RELAY_WEBHOOK_URL", "").strip()
EVENT_NAME = os.environ.get("GITHUB_EVENT_NAME", "repository_dispatch")
GEMINI_MODELS = ["gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash"]

ARCH_MAP = """
Arquitectura de EnduLink (Kotlin Multiplatform Android + iOS):
- shared/src/commonMain/kotlin/com/oscar/endulink/ui/EnduController.kt: controlador principal, GPS, grabación REC, gestión de rutas y conexión moto.
- shared/src/commonMain/kotlin/com/oscar/endulink/ui/InteractiveMap.kt: renderizado del mapa en móvil, pistas GPX, waypoints y gestos.
- shared/src/commonMain/kotlin/com/oscar/endulink/ui/HudOverlay.kt: interfaz HUD superior/lateral/inferior personalizable y tarjeta de destino.
- shared/src/commonMain/kotlin/com/oscar/endulink/ui/RouteGeneratorSheet.kt: generador de rutas A->B y loops circulares estilo DMD2.
- shared/src/commonMain/kotlin/com/oscar/endulink/ui/AppSettingsScreen.kt: pantalla de ajustes, escalado de fuentes, layout HUD y actualizaciones.
- shared/src/commonMain/kotlin/com/oscar/endulink/trail/GPXParser.kt & GPXModels.kt & RouteStore.kt: parser XML lineal de GPX masivos y persistencia.
- shared/src/commonMain/kotlin/com/oscar/endulink/trail/RoadRouter.kt: motor BRouter (carretera/curvas/trail/offroad/enduro) + OSRM + fallback offline + Loops.
- shared/src/commonMain/kotlin/com/oscar/endulink/trail/RouteNavigator.kt: proyección ortogonal sobre GPX y avisos fuera de track.
- shared/src/commonMain/kotlin/com/oscar/endulink/bike/BikeLink.kt & CommonBikeMapScene.kt: protocolo TCP/H.264 con pantalla TFT de moto (EasyConn/Carbit).
- shared/src/commonMain/kotlin/com/oscar/endulink/tiles/TileStore.kt & TileDiskStorage.kt: descarga y caché de mapas offline.
- shared/src/commonMain/kotlin/com/oscar/endulink/group/GroupManager.kt: grupos en tiempo real (MQTT + LAN).
- iosApp/iosApp/iOSApp.swift & androidApp/src/main/java/com/oscar/endulink/MainActivity.kt: integración nativa iOS/Android.
"""


def gh_api(method: str, path: str, payload=None):
    url = f"https://api.github.com{path}"
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {GITHUB_TOKEN}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=20) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw.strip() else {}


def post_json(url: str, payload: dict) -> bool:
    try:
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=15) as resp:
            return 200 <= resp.status < 300
    except Exception as exc:
        print(f"Webhook callback warning: {exc}")
        return False


def fetch_recent_issues(state: str = "open", limit: int = 80) -> list[dict]:
    try:
        items = gh_api("GET", f"/repos/{REPO}/issues?state={state}&per_page={limit}")
        return [it for it in items if "pull_request" not in it]
    except Exception as exc:
        print(f"Failed to list issues ({state}): {exc}")
        return []


def fetch_open_issues() -> list[dict]:
    return fetch_recent_issues(state="open", limit=80)


def find_issue_by_report_id(report_id: str, issues: list[dict]) -> int | None:
    if not report_id:
        return None
    marker = f"endulink-report-id: {report_id}"
    for issue in issues:
        if marker in (issue.get("body") or ""):
            return issue.get("number")
    return None


def ensure_labels(labels: list[str]):
    palette = {
        "bug": "d73a4a",
        "enhancement": "0075ca",
        "area:tft": "ff6b00",
        "area:mapas": "0e8a16",
        "area:grupos": "5319e7",
        "ia-triage": "1d76db",
        "crash": "b60205",
    }
    for lb in labels:
        try:
            gh_api("POST", f"/repos/{REPO}/labels", {
                "name": lb,
                "color": palette.get(lb, "ededed"),
            })
        except urllib.error.HTTPError:
            pass
        except Exception:
            pass


def call_gemini(prompt: str) -> dict | None:
    if not GEMINI_API_KEY:
        return None
    body = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"responseMimeType": "application/json", "temperature": 0.2},
    }
    # Se prueban varios alias porque Google retira/renombra modelos (HTTP 404)
    for model in GEMINI_MODELS:
        try:
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={GEMINI_API_KEY}"
            req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), method="POST")
            req.add_header("Content-Type", "application/json")
            with urllib.request.urlopen(req, timeout=35) as resp:
                parsed = json.loads(resp.read().decode("utf-8"))
                text = parsed["candidates"][0]["content"]["parts"][0]["text"]
                result = json.loads(text)
                if isinstance(result, dict):
                    return result
        except Exception as exc:
            print(f"Gemini model '{model}' fallback ({exc})")
    return None


def _normalize_text(text: str) -> str:
    return " ".join(re.findall(r"[a-z0-9áéíóúüñ]+", (text or "").lower()))


def _extract_user_fields_from_issue(issue: dict) -> tuple[str, str]:
    """Extrae título y mensaje original del usuario del cuerpo generado por EnduLink."""
    issue_title = re.sub(r"^\[[^\]]+\]\s*", "", issue.get("title") or "").strip()
    body = issue.get("body") or ""
    title_match = re.search(r"\*\*Título usuario:\*\*\s*(.+)", body)
    extracted_title = title_match.group(1).strip() if title_match else issue_title
    if extracted_title == "(sin título)":
        extracted_title = issue_title

    msg_match = re.search(
        r"## Mensaje Original del Usuario \(Intacto\)(.*?)(?:\n<details>|\n<!-- endulink-report-id:|\Z)",
        body,
        flags=re.DOTALL,
    )
    if msg_match:
        block_lines = [
            line.lstrip("> ").strip()
            for line in msg_match.group(1).splitlines()
            if line.strip().startswith(">") and "**Título usuario:**" not in line and "**Categoría:**" not in line and not line.strip().startswith("> - **Ubicación")
        ]
        extracted_msg = " ".join(ln for ln in block_lines if ln)
    else:
        extracted_msg = body[:400]

    return extracted_title or issue_title, extracted_msg


def heuristic_triage(report: dict, open_issues: list[dict]) -> dict:
    raw_msg = (report.get("userMessage") or "").strip()
    raw_title = (report.get("userTitle") or "").strip()
    norm_title = _normalize_text(raw_title)
    norm_msg = _normalize_text(raw_msg)
    norm_combined = _normalize_text(f"{raw_title} {raw_msg}")
    combined = f"{raw_title} {raw_msg}".lower()
    combined_tokens = set(norm_combined.split())

    best_num = None
    best_score = 0.0
    for issue in open_issues:
        cand_title, cand_msg = _extract_user_fields_from_issue(issue)
        cand_norm_title = _normalize_text(cand_title)
        cand_norm_msg = _normalize_text(cand_msg)
        cand_combined = _normalize_text(f"{cand_title} {cand_msg}")
        cand_tokens = set(cand_combined.split())

        if (norm_msg and cand_norm_msg == norm_msg) or (
            norm_title and norm_msg and cand_norm_title == norm_title and cand_norm_msg == norm_msg
        ):
            best_score = 1.0
            best_num = issue.get("number")
            break

        seq_ratio = difflib.SequenceMatcher(None, norm_combined[:300], cand_combined[:300]).ratio() if norm_combined and cand_combined else 0.0
        msg_ratio = difflib.SequenceMatcher(None, norm_msg[:300], cand_norm_msg[:300]).ratio() if norm_msg and cand_norm_msg else 0.0
        union = combined_tokens | cand_tokens
        jaccard = (len(combined_tokens & cand_tokens) / len(union)) if union else 0.0
        score = max(seq_ratio, msg_ratio, jaccard)
        if score > best_score:
            best_score = score
            best_num = issue.get("number")

    is_dup = best_num is not None and best_score >= 0.72
    cat = report.get("category", "BUG")
    labels = ["ia-triage"]
    if cat == "IMPROVEMENT":
        labels.append("enhancement")
    else:
        labels.append("bug")
    if report.get("crashLog"):
        labels.append("crash")

    hints = []
    if any(k in combined for k in ("gpx", "track", "puerto", "ruta", "loop", "brouter")):
        labels.append("area:mapas")
        hints.append("Revisar `GPXParser.kt` / `RoadRouter.kt` / `RouteNavigator.kt` y validar con datos de prueba.")
    if any(k in combined for k in ("tft", "moto", "wifi", "qr", "pantalla")):
        labels.append("area:tft")
        hints.append("Revisar estado TCP/Wi-Fi y logs en `BikeLink.kt` / `EnduController.kt`.")
    if any(k in combined for k in ("grupo", "mqtt", "compañero", "sos")):
        labels.append("area:grupos")
        hints.append("Revisar sincronización en `GroupManager.kt` y `GroupTransport.kt`.")
    if not hints:
        hints.append("Reproducir en el módulo afectado dentro de `EnduController.kt` / UI Compose y añadir test unitario.")

    clean_title = raw_title or (raw_msg.splitlines()[0][:72] if raw_msg else "Reporte de usuario")
    prefix = "[Mejora]" if cat == "IMPROVEMENT" else "[Bug]"
    if not clean_title.startswith("["):
        clean_title = f"{prefix} {clean_title}"

    return {
        "is_duplicate": is_dup,
        "duplicate_issue_number": best_num if is_dup else None,
        "clean_title": clean_title[:100],
        "labels": sorted(set(labels)),
        "technical_summary": f"Reporte clasificado en categoría `{cat}`. Descripción del usuario analizada pendiente de verificación manual en entorno de reproducción.",
        "root_cause_hypothesis": "Pendiente de confirmación exacta mediante los logs adjuntos o reproducción en `EnduController.kt` / módulo de área.",
        "solution_plan": hints,
    }


def build_prompt(report: dict, open_issues: list[dict]) -> str:
    issues_brief = [
        {"number": i["number"], "title": i["title"], "snippet": (i.get("body") or "")[:400]}
        for i in open_issues[:50]
    ]
    return f"""
Analiza este reporte enviado por un usuario desde la app EnduLink.
Tu tarea:
1. Comprobar si el problema o mejora YA está recogido en alguno de los Issues abiertos (`open_issues`). Sé estricto para evitar duplicados pero no fusiones bugs distintos.
2. Si NO es duplicado, redactar un título técnico claro en español y un resumen profesional, formulando una hipótesis de causa raíz y un plan de solución concreto citando archivos reales de EnduLink.
3. Si ES duplicado, indicar el número de issue existente (`duplicate_issue_number`).

{ARCH_MAP}

Issues abiertos actuales:
{json.dumps(issues_brief, ensure_ascii=False)}

Reporte recibido:
{json.dumps(report, ensure_ascii=False)}

Devuelve JSON estricto con esta estructura exacta:
{{
  "is_duplicate": false,
  "duplicate_issue_number": null,
  "clean_title": "[Bug/Mejora · Área] Título conciso",
  "labels": ["bug", "ia-triage"],
  "technical_summary": "Explicación técnica clara y bien redactada en español",
  "root_cause_hypothesis": "Qué componente/flujo parece estar implicado y por qué",
  "solution_plan": [
    "Paso 1 con archivo concreto...",
    "Paso 2..."
  ]
}}
"""


def format_issue_body(report: dict, ai: dict) -> str:
    plan_md = "\n".join(f"- {step}" for step in (ai.get("solution_plan") or []))
    user_msg = report.get("userMessage") or ""
    user_title = report.get("userTitle") or "(sin título)"
    user_contact = report.get("userContact") or "Anónimo"
    app_ver = report.get("appVersion", "?")
    platform = report.get("platformName", "?")
    channel = "test" if report.get("isTestChannel") else "release"

    pin = report.get("pinCoordinate")
    pin_block = ""
    if isinstance(pin, dict) and "latitude" in pin and "longitude" in pin:
        lat, lon = pin["latitude"], pin["longitude"]
        addr = report.get("pinAddress") or f"{lat:.5f}, {lon:.5f}"
        pin_block = f"\n- **Ubicación marcada:** {addr} ([Ver en OpenStreetMap](https://www.openstreetmap.org/?mlat={lat}&mlon={lon}#map=15/{lat}/{lon}))"

    diag = report.get("diagnosticsSummary") or ""
    logs = "\n".join(report.get("recentBikeLogs") or [])
    crash = report.get("crashLog") or ""

    sections = [
        f"## Resumen Técnico (Agente IA)\n{ai.get('technical_summary', '')}",
        f"## Hipótesis de Causa Raíz\n{ai.get('root_cause_hypothesis', '')}",
        f"## Plan de Solución Propuesto\n{plan_md}",
        "## Mensaje Original del Usuario (Intacto)\n"
        f"> **Título usuario:** {user_title}\n"
        f"> **Categoría:** `{report.get('category', 'BUG')}` · **App:** `v{app_ver} ({channel})` en `{platform}` · **Contacto:** `{user_contact}`{pin_block}\n>\n"
        + "\n".join(f"> {line}" for line in user_msg.splitlines()),
    ]

    if diag:
        sections.append(f"<details>\n<summary>Diagnóstico técnico automático</summary>\n\n```\n{diag}\n```\n</details>")
    if crash:
        sections.append(f"<details>\n<summary>Registro de cierre inesperado (Crash Log)</summary>\n\n```\n{crash}\n```\n</details>")
    if logs:
        sections.append(f"<details>\n<summary>Últimos logs TFT / conexión moto</summary>\n\n```\n{logs}\n```\n</details>")
    report_id = (report.get("reportId") or "").strip()
    if report_id:
        sections.append(f"<!-- endulink-report-id: {report_id} -->")

    return "\n\n".join(sections)


def format_duplicate_comment(report: dict, ai: dict) -> str:
    user_msg = report.get("userMessage") or ""
    app_ver = report.get("appVersion", "?")
    platform = report.get("platformName", "?")
    user_contact = report.get("userContact") or "Anónimo"
    diag = report.get("diagnosticsSummary") or ""
    crash = report.get("crashLog") or ""

    out = [
        "### 🔄 Nuevo reporte agrupado automáticamente (+1 usuario afectado)",
        f"- **Versión / Plataforma:** `v{app_ver}` · `{platform}` · **Contacto:** `{user_contact}`",
        f"- **Nota IA:** {ai.get('technical_summary', '')}",
        "\n**Mensaje original del usuario:**\n" + "\n".join(f"> {line}" for line in user_msg.splitlines()),
    ]
    if diag:
        out.append(f"<details><summary>Diagnóstico adjunto</summary>\n\n```\n{diag}\n```\n</details>")
    if crash:
        out.append(f"<details><summary>Crash log adjunto</summary>\n\n```\n{crash}\n```\n</details>")
    report_id = (report.get("reportId") or "").strip()
    if report_id:
        out.append(f"<!-- endulink-report-id: {report_id} -->")
    return "\n\n".join(out)


def handle_dispatch():
    raw_dispatch = os.environ.get("DISPATCH_PAYLOAD", "{}")
    payload = json.loads(raw_dispatch) if raw_dispatch.strip() else {}
    report = payload.get("report") if isinstance(payload.get("report"), dict) else payload
    if not report or not report.get("userMessage"):
        print("Empty dispatch report; nothing to process.")
        return

    report_id = (report.get("reportId") or "").strip()
    recent_all_issues = fetch_recent_issues(state="all", limit=100)
    already_issue_num = find_issue_by_report_id(report_id, recent_all_issues)
    if already_issue_num is not None:
        print(f"Report {report_id} already processed in issue #{already_issue_num}; skipping duplicate dispatch.")
        return

    open_issues = [it for it in recent_all_issues if it.get("state") == "open"]
    prompt = build_prompt(report, open_issues)
    ai = call_gemini(prompt) or heuristic_triage(report, open_issues)

    labels = list({*(ai.get("labels") or []), "ia-triage"})
    ensure_labels(labels)

    dup_num = ai.get("duplicate_issue_number")
    valid_open_nums = {it["number"] for it in open_issues}
    issue_url = ""
    issue_number = None
    action_type = "created"

    if ai.get("is_duplicate") and isinstance(dup_num, int) and dup_num in valid_open_nums:
        comment_body = format_duplicate_comment(report, ai)
        res = gh_api("POST", f"/repos/{REPO}/issues/{dup_num}/comments", {"body": comment_body})
        issue_number = dup_num
        issue_url = res.get("html_url", f"https://github.com/{REPO}/issues/{dup_num}")
        action_type = "deduplicated_comment"
    else:
        body = format_issue_body(report, ai)
        created = gh_api("POST", f"/repos/{REPO}/issues", {
            "title": ai.get("clean_title") or report.get("userTitle") or "Reporte de usuario",
            "body": body,
            "labels": labels,
        })
        issue_number = created.get("number")
        issue_url = created.get("html_url", "")

    callback_url = (payload.get("callbackUrl") or SECRET_RELAY_URL or "").strip()
    if callback_url.startswith("https://"):
        post_json(callback_url, {
            "mode": "ai_triage_email",
            "actionType": action_type,
            "issueNumber": issue_number,
            "issueUrl": issue_url,
            "cleanTitle": ai.get("clean_title"),
            "technicalSummary": ai.get("technical_summary"),
            "rootCauseHypothesis": ai.get("root_cause_hypothesis"),
            "solutionPlan": ai.get("solution_plan") or [],
            "userMessage": report.get("userMessage"),
            "userContact": report.get("userContact") or "Anónimo",
            "appVersion": report.get("appVersion", "?"),
            "platformName": report.get("platformName", "?"),
        })


def handle_manual_issue():
    raw_issue = os.environ.get("ISSUE_JSON", "{}")
    issue = json.loads(raw_issue) if raw_issue.strip() else {}
    number = issue.get("number")
    title = issue.get("title") or ""
    body = issue.get("body") or ""
    labels = {lb.get("name", "") for lb in (issue.get("labels") or [])}
    if not number or "ia-triage" in labels:
        return

    open_issues = [it for it in fetch_open_issues() if it.get("number") != number]
    pseudo_report = {
        "category": "IMPROVEMENT" if "enhancement" in labels else "BUG",
        "userTitle": title,
        "userMessage": body or title,
        "appVersion": "web",
        "platformName": "GitHub",
        "isTestChannel": False,
    }
    prompt = build_prompt(pseudo_report, open_issues)
    ai = call_gemini(prompt) or heuristic_triage(pseudo_report, open_issues)
    plan_md = "\n".join(f"- {step}" for step in (ai.get("solution_plan") or []))
    comment = (
        f"## Análisis del Agente IA\n"
        f"**Resumen:** {ai.get('technical_summary', '')}\n\n"
        f"**Hipótesis:** {ai.get('root_cause_hypothesis', '')}\n\n"
        f"**Plan de solución propuesto:**\n{plan_md}"
    )
    if ai.get("is_duplicate") and ai.get("duplicate_issue_number"):
        comment += f"\n\n> ⚠️ Posible duplicado del Issue abierto **#{ai['duplicate_issue_number']}**."
    gh_api("POST", f"/repos/{REPO}/issues/{number}/comments", {"body": comment})


if __name__ == "__main__":
    if EVENT_NAME == "issues":
        handle_manual_issue()
    else:
        handle_dispatch()
