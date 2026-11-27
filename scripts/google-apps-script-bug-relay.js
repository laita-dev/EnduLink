/**
 * Puente gratuito Google Apps Script para EnduLink:
 * - Recibe los reportes desde la app Android/iOS (sin meter ningún token secreto en la app).
 * - Dispara el workflow `triage-bug-report.yml` en GitHub mediante `repository_dispatch`.
 * - Envía el email formateado a laitaodev@gmail.com cuando el Agente IA termina (o si falla el dispatch).
 *
 * CONFIGURACIÓN EN 2 MINUTOS:
 * 1. Abre https://script.google.com -> "Nuevo proyecto" y pega este código completo.
 * 2. Ve a Configuración del proyecto (engranaje) -> Propiedades del script -> Añadir:
 *      - `GITHUB_TOKEN`: Personal Access Token (Fine-grained) de tu repo `laita-dev/EnduLink` con permiso Contents: Read/Write (o Actions: Write).
 *      - `GITHUB_REPO`: `laita-dev/EnduLink`
 *      - `NOTIFY_EMAIL`: `laitaodev@gmail.com`
 * 3. Pulsa "Implementar" -> "Nueva implementación" -> Tipo: "Aplicación web":
 *      - Ejecutar como: "Yo"
 *      - Quién tiene acceso: "Cualquier usuario" (Anyone)
 * 4. Copia la URL `https://script.google.com/macros/s/.../exec` y pégala en:
 *      - `BugReportManager.DEFAULT_RELAY_WEBHOOK_URL` (o en Ajustes -> Soporte y Reportes)
 *      - Opcionalmente en GitHub Secrets como `BUG_RELAY_WEBHOOK_URL`
 */

function doPost(e) {
  try {
    var raw = (e && e.postData && e.postData.contents) ? e.postData.contents : '{}';
    var data = JSON.parse(raw);
    var props = PropertiesService.getScriptProperties();
    var notifyEmail = props.getProperty('NOTIFY_EMAIL') || 'laitaodev@gmail.com';
    var ghToken = props.getProperty('GITHUB_TOKEN') || '';
    var ghRepo = props.getProperty('GITHUB_REPO') || 'laita-dev/EnduLink';

    // Caso A: Callback de regreso desde GitHub Actions tras terminar el análisis del Agente IA
    if (data && data.mode === 'ai_triage_email') {
      sendAiTriageEmail(notifyEmail, data);
      return jsonResponse({ ok: true, mode: 'ai_triage_email' });
    }

    // Caso B: Reporte nuevo enviado directamente desde la app EnduLink
    var callbackUrl = '';
    try {
      callbackUrl = ScriptApp.getService().getUrl() || '';
    } catch (_) {}

    var dispatched = false;
    if (ghToken) {
      var ghUrl = 'https://api.github.com/repos/' + ghRepo + '/dispatches';
      var ghPayload = {
        event_type: 'user_bug_report',
        client_payload: {
          report: data,
          callbackUrl: callbackUrl
        }
      };
      var resp = UrlFetchApp.fetch(ghUrl, {
        method: 'post',
        contentType: 'application/json',
        headers: {
          Authorization: 'Bearer ' + ghToken,
          Accept: 'application/vnd.github+json',
          'X-GitHub-Api-Version': '2022-11-28'
        },
        payload: JSON.stringify(ghPayload),
        muteHttpExceptions: true
      });
      dispatched = resp.getResponseCode() >= 200 && resp.getResponseCode() < 300;
    }

    // Si aún no está configurado GITHUB_TOKEN o falla el dispatch, enviamos igual el reporte por email directo
    if (!dispatched) {
      sendFallbackDirectEmail(notifyEmail, data);
    }

    return jsonResponse({ ok: true, dispatchedToGitHub: dispatched });
  } catch (err) {
    return jsonResponse({ ok: false, error: String(err) });
  }
}

function sendAiTriageEmail(email, data) {
  var isDup = data.actionType === 'deduplicated_comment';
  var prefix = isDup ? '[EnduLink · Reporte agrupado #' : '[EnduLink · Nuevo Issue #';
  var subject = prefix + (data.issueNumber || '?') + '] ' + (data.cleanTitle || 'Reporte');
  var planItems = (data.solutionPlan || []).map(function (s) {
    return '<li>' + escapeHtml(s) + '</li>';
  }).join('');

  var html =
    '<div style="font-family:Arial,sans-serif;max-width:680px;line-height:1.5;color:#1b1f23">' +
      '<h2 style="margin-bottom:4px;color:#ff600a">' + escapeHtml(subject) + '</h2>' +
      '<p style="margin-top:0;color:#586069">App v' + escapeHtml(data.appVersion || '?') + ' · ' + escapeHtml(data.platformName || '?') + ' · Contacto: ' + escapeHtml(data.userContact || 'Anónimo') + '</p>' +
      (data.issueUrl ? '<p><a href="' + escapeHtml(data.issueUrl) + '" style="background:#ff600a;color:#fff;padding:8px 14px;text-decoration:none;border-radius:6px;font-weight:bold">Abrir Issue en GitHub</a></p>' : '') +
      '<h3>Resumen IA</h3><p>' + escapeHtml(data.technicalSummary || '') + '</p>' +
      '<h3>Causa raíz probable</h3><p>' + escapeHtml(data.rootCauseHypothesis || '') + '</p>' +
      (planItems ? '<h3>Plan de solución propuesto</h3><ul>' + planItems + '</ul>' : '') +
      '<h3>Mensaje original del usuario (intacto)</h3>' +
      '<blockquote style="border-left:4px solid #ff600a;margin:0;padding:8px 12px;background:#f6f8fa">' +
        escapeHtml(data.userMessage || '').replace(/\n/g, '<br>') +
      '</blockquote>' +
    '</div>';

  MailApp.sendEmail({
    to: email,
    subject: subject,
    htmlBody: html
  });
}

function sendFallbackDirectEmail(email, report) {
  var title = report.userTitle || (report.category || 'Reporte');
  var subject = '[EnduLink · Reporte directo] ' + title;
  var body =
    'Categoría: ' + (report.category || '?') + '\n' +
    'App: v' + (report.appVersion || '?') + ' (' + (report.platformName || '?') + ')\n' +
    'Contacto: ' + (report.userContact || 'Anónimo') + '\n\n' +
    '--- MENSAJE ORIGINAL DEL USUARIO ---\n' + (report.userMessage || '') + '\n\n' +
    '--- DIAGNÓSTICO ---\n' + (report.diagnosticsSummary || '') + '\n\n' +
    (report.crashLog ? '--- CRASH LOG ---\n' + report.crashLog + '\n' : '');
  MailApp.sendEmail(email, subject, body);
}

function escapeHtml(str) {
  return String(str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
