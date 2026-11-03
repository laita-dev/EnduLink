package com.oscar.endulink.tiles

import com.oscar.endulink.trail.GPXRoute
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class OfflineDownloadProgress(
    val isDownloading: Boolean = false,
    val label: String = "",
    val completed: Int = 0,
    val total: Int = 0,
    val failed: Int = 0
)

data class OverzoomBitmapResult(
    val bitmap: androidx.compose.ui.graphics.ImageBitmap,
    val subX: Int,
    val subY: Int,
    val scale: Int
)

data class OverzoomResult(
    val parentTileBytes: ByteArray,
    val parentZ: Int,
    val parentX: Int,
    val parentY: Int,
    val delta: Int,
    val subX: Int,
    val subY: Int,
    val scale: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OverzoomResult) return false
        return delta == other.delta && subX == other.subX && subY == other.subY &&
                scale == other.scale && parentTileBytes.contentEquals(other.parentTileBytes)
    }

    override fun hashCode(): Int {
        var result = parentTileBytes.contentHashCode()
        result = 31 * result + delta
        result = 31 * result + subX
        result = 31 * result + subY
        result = 31 * result + scale
        return result
    }
}

class TileStore(
    private val httpClient: HttpClient = HttpClient()
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val inFlight = mutableSetOf<TileCoord>()
    private val inFlightLock = Any()

    @Volatile
    private var isCancelled = false

    var onTileLoaded: (() -> Unit)? = null

    // Caché LRU de Bitmaps ya decodificados para render instantáneo en 60 FPS (1024 teselas = cubre varios niveles de zoom sin parpadeos)
    private val bitmapCacheLock = Any()
    private val maxBitmapCache = 1024
    private val bitmapCache = object : LinkedHashMap<TileCoord, androidx.compose.ui.graphics.ImageBitmap>(maxBitmapCache, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileCoord, androidx.compose.ui.graphics.ImageBitmap>?): Boolean {
            return size > maxBitmapCache
        }
    }

    // Caché LRU en memoria de bytes (512 teselas)
    private val memoryCacheLock = Any()
    private val maxMemoryTiles = 512
    private val memoryCache = object : LinkedHashMap<TileCoord, ByteArray>(maxMemoryTiles, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileCoord, ByteArray>?): Boolean {
            return size > maxMemoryTiles
        }
    }

    // Registro persistente de paquetes y zonas descargadas
    private val _downloadedPackages = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val downloadedPackages: kotlinx.coroutines.flow.StateFlow<Set<String>> = _downloadedPackages

    private val packagesFilePath: String
        get() = "${PlatformPaths.getTileCacheDir()}/downloaded_packages.txt"

    init {
        loadDownloadedPackages()
    }

    private fun loadDownloadedPackages() {
        try {
            val bytes = TileDiskStorage.readBytes(packagesFilePath)
            if (bytes != null && bytes.isNotEmpty()) {
                val text = bytes.decodeToString()
                val set = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                _downloadedPackages.value = set
            }
        } catch (_: Exception) {}
    }

    fun markPackageDownloaded(packageKey: String) {
        val updated = _downloadedPackages.value + packageKey
        _downloadedPackages.value = updated
        saveDownloadedPackages(updated)
    }

    fun removePackage(packageKey: String) {
        val updated = _downloadedPackages.value - packageKey
        _downloadedPackages.value = updated
        saveDownloadedPackages(updated)
    }

    private fun saveDownloadedPackages(set: Set<String>) {
        try {
            val text = set.joinToString("\n")
            TileDiskStorage.writeBytes(packagesFilePath, text.encodeToByteArray())
        } catch (_: Exception) {}
    }

    // Carga asíncrona de bitmaps para el mapa interactivo (nunca bloquea el hilo de UI)
    private val bitmapLoading = mutableSetOf<TileCoord>()
    private val failedAt = mutableMapOf<TileCoord, Long>()
    private val networkSemaphore = Semaphore(10)

    /** Devuelve el bitmap solo si ya está decodificado en RAM (cero E/S). */
    fun peekBitmap(coord: TileCoord): androidx.compose.ui.graphics.ImageBitmap? =
        synchronized(bitmapCacheLock) { bitmapCache[coord] }

    /**
     * Versión no bloqueante para el render a 60 FPS: si el bitmap no está en RAM,
     * lanza la lectura de disco / descarga + decodificación en segundo plano y devuelve null.
     * Al terminar invoca [onTileLoaded] para repintar.
     */
    fun requestBitmap(coord: TileCoord, allowNetwork: Boolean = true): androidx.compose.ui.graphics.ImageBitmap? {
        peekBitmap(coord)?.let { return it }
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val canStart = synchronized(inFlightLock) {
            val lastFail = failedAt[coord]
            if (lastFail != null && now - lastFail < 3_000L) false else bitmapLoading.add(coord)
        }
        if (!canStart) return null

        scope.launch(Dispatchers.Default) {
            var ok = false
            try {
                var bytes: ByteArray? = synchronized(memoryCacheLock) { memoryCache[coord] }
                    ?: TileDiskStorage.readBytes(diskPath(coord))?.takeIf { it.size > 100 }
                if (bytes == null && allowNetwork) {
                    bytes = networkSemaphore.withPermit { downloadTileBytes(coord) }
                }
                if (bytes != null) {
                    synchronized(memoryCacheLock) { memoryCache[coord] = bytes }
                    val bmp = decodeByteArrayToBitmap(bytes)
                    if (bmp != null) {
                        synchronized(bitmapCacheLock) { bitmapCache[coord] = bmp }
                        ok = true
                        onTileLoaded?.invoke()
                    }
                }
            } catch (_: Exception) {
            } finally {
                synchronized(inFlightLock) {
                    bitmapLoading.remove(coord)
                    if (ok) failedAt.remove(coord) else if (allowNetwork) failedAt[coord] = now
                }
            }
        }
        return null
    }

    private suspend fun downloadTileBytes(coord: TileCoord): ByteArray? {
        val url = coord.layer.tileURL(coord.z, coord.x, coord.y) ?: return null
        val response = httpClient.get(url) {
            headers {
                append(HttpHeaders.UserAgent, "EnduLink/0.1.0 (Mobile Trail Navigation; https://github.com)")
                append(HttpHeaders.Accept, "image/png,image/jpeg,image/*;q=0.9,*/*;q=0.8")
            }
        }
        if (response.status != HttpStatusCode.OK) return null
        val bytes = response.readBytes()
        if (bytes.size <= 100) return null
        TileDiskStorage.writeBytes(diskPath(coord), bytes)
        return bytes
    }

    fun diskPath(coord: TileCoord): String {
        return "${PlatformPaths.getTileCacheDir()}/${coord.layer.raw}/${coord.z}/${coord.x}/${coord.y}.tile"
    }

    /**
     * Obtiene el ImageBitmap decodificado para Compose DrawScope.
     */
    fun getTileBitmap(coord: TileCoord, allowAsyncFetch: Boolean = true): androidx.compose.ui.graphics.ImageBitmap? {
        synchronized(bitmapCacheLock) {
            val inCache = bitmapCache[coord]
            if (inCache != null) return inCache
        }

        val bytes = getTileBytes(coord, allowAsyncFetch) ?: return null
        val decoded = decodeByteArrayToBitmap(bytes) ?: return null

        synchronized(bitmapCacheLock) {
            bitmapCache[coord] = decoded
        }
        return decoded
    }

    /**
     * Obtiene los bytes de una tesela.
     * Busca primero en RAM, luego en Disco, y si no existe dispara la descarga asíncrona.
     */
    fun getTileBytes(coord: TileCoord, allowAsyncFetch: Boolean = true): ByteArray? {
        // 1. RAM
        synchronized(memoryCacheLock) {
            val inRam = memoryCache[coord]
            if (inRam != null) return inRam
        }

        // 2. Disco
        val path = diskPath(coord)
        val fromDisk = TileDiskStorage.readBytes(path)
        if (fromDisk != null && fromDisk.size > 100) {
            synchronized(memoryCacheLock) {
                memoryCache[coord] = fromDisk
            }
            return fromDisk
        }

        // 3. Descarga asíncrona
        if (allowAsyncFetch) {
            fetchTileAsync(coord)
        }
        return null
    }

    /**
     * Algoritmo de Sobre-Escalado (*Parent Overzoom*):
     * Si la tesela (z, x, y) no está disponible en disco ni memoria, busca teselas padre
     * en z-1, z-2, z-3, z-4 para recortar y escalar el cuadrante correspondiente.
     * Evita huecos negros en la pantalla de la moto y en el móvil cuando no hay cobertura.
     */
    fun getOverzoomTile(layer: MapLayer, z: Int, x: Int, y: Int): OverzoomResult? {
        return getOverzoomedParent(TileCoord(layer, z, x, y))
    }

    /**
     * Obtiene directamente el ImageBitmap del padre sobre-escalado, decodificándolo
     * si solo estaba en bytes de disco para que nunca aparezcan cuadros negros al cambiar zoom.
     */
    fun getOverzoomBitmap(coord: TileCoord): OverzoomBitmapResult? {
        val oz = getOverzoomedParent(coord) ?: return null
        val parentCoord = TileCoord(coord.layer, oz.parentZ, oz.parentX, oz.parentY)
        var parentBmp = peekBitmap(parentCoord)
        if (parentBmp == null) {
            parentBmp = decodeByteArrayToBitmap(oz.parentTileBytes)
            if (parentBmp != null) {
                synchronized(bitmapCacheLock) {
                    bitmapCache[parentCoord] = parentBmp
                }
            }
        }
        return if (parentBmp != null) {
            OverzoomBitmapResult(
                bitmap = parentBmp,
                subX = oz.subX,
                subY = oz.subY,
                scale = oz.scale
            )
        } else null
    }

    fun getOverzoomedParent(coord: TileCoord): OverzoomResult? {
        for (delta in 1..4) {
            val pZ = coord.z - delta
            if (pZ < 1) break
            val pX = coord.x shr delta
            val pY = coord.y shr delta
            val parentCoord = TileCoord(coord.layer, pZ, pX, pY)

            // Buscar padre en RAM o Disco (sin disparar descarga del padre)
            val parentBytes = synchronized(memoryCacheLock) { memoryCache[parentCoord] }
                ?: TileDiskStorage.readBytes(diskPath(parentCoord))

            if (parentBytes != null && parentBytes.size > 100) {
                val scale = 1 shl delta
                val subX = coord.x and (scale - 1)
                val subY = coord.y and (scale - 1)
                return OverzoomResult(
                    parentTileBytes = parentBytes,
                    parentZ = pZ,
                    parentX = pX,
                    parentY = pY,
                    delta = delta,
                    subX = subX,
                    subY = subY,
                    scale = scale
                )
            }
        }
        return null
    }

    private fun fetchTileAsync(coord: TileCoord) {
        val inserted = synchronized(inFlightLock) { inFlight.add(coord) }
        if (!inserted) return
        val url = coord.layer.tileURL(coord.z, coord.x, coord.y) ?: run {
            synchronized(inFlightLock) { inFlight.remove(coord) }
            return
        }

        scope.launch(Dispatchers.Default) {
            try {
                val response = httpClient.get(url) {
                    headers {
                        append(HttpHeaders.UserAgent, "EnduLink/0.1.0 (Mobile Trail Navigation; https://github.com)")
                        append(HttpHeaders.Accept, "image/png,image/jpeg,image/*;q=0.9,*/*;q=0.8")
                    }
                }
                if (response.status == HttpStatusCode.OK) {
                    val bytes = response.readBytes()
                    if (bytes.size > 100) {
                        synchronized(memoryCacheLock) {
                            memoryCache[coord] = bytes
                        }
                        TileDiskStorage.writeBytes(diskPath(coord), bytes)
                        onTileLoaded?.invoke()
                    }
                }
            } catch (_: Exception) {
            } finally {
                synchronized(inFlightLock) { inFlight.remove(coord) }
            }
        }
    }

    fun downloadCorridor(
        route: GPXRoute,
        layer: MapLayer,
        onProgress: (OfflineDownloadProgress) -> Unit
    ) {
        val tiles = TileMath.estimateCorridorTiles(route.points, layer)
        downloadTiles(tiles = tiles, label = route.name, packageKey = null, onProgress = onProgress)
    }

    fun downloadBoundingBox(
        bbox: MapBoundingBox,
        layer: MapLayer,
        minZ: Int,
        maxZ: Int,
        label: String,
        packageKey: String? = null,
        onProgress: (OfflineDownloadProgress) -> Unit
    ) {
        val tiles = TileMath.enumerateBoundingBoxTiles(bbox, layer, minZ, maxZ)
        downloadTiles(tiles, label, packageKey, onProgress)
    }

    fun cancelDownload() {
        isCancelled = true
    }

    private fun downloadTiles(
        tiles: List<TileCoord>,
        label: String,
        packageKey: String? = null,
        onProgress: (OfflineDownloadProgress) -> Unit
    ) {
        if (tiles.isEmpty()) {
            onProgress(OfflineDownloadProgress(isDownloading = false, label = label, completed = 0, total = 0, failed = 0))
            return
        }

        isCancelled = false
        val total = tiles.size
        val concurrency = tiles.firstOrNull()?.layer?.recommendedConcurrency ?: 3
        val semaphore = Semaphore(concurrency)

        scope.launch(Dispatchers.Default) {
            var completed = 0
            var failed = 0
            onProgress(OfflineDownloadProgress(isDownloading = true, label = label, completed = 0, total = total, failed = 0))

            val jobs = tiles.map { coord ->
                launch {
                    if (isCancelled) return@launch

                    val path = diskPath(coord)
                    if (TileDiskStorage.fileExists(path) && TileDiskStorage.fileSize(path) > 120) {
                        val c = synchronized(this@TileStore) { ++completed }
                        if (c % 20 == 0 || c == total) {
                            onProgress(OfflineDownloadProgress(isDownloading = true, label = label, completed = c, total = total, failed = failed))
                        }
                        return@launch
                    }

                    val url = coord.layer.tileURL(coord.z, coord.x, coord.y) ?: run {
                        synchronized(this@TileStore) { ++failed }
                        return@launch
                    }

                    semaphore.withPermit {
                        if (isCancelled) return@launch
                        var success = false
                        for (attempt in 0..1) {
                            try {
                                val resp = httpClient.get(url) {
                                    headers {
                                        append(HttpHeaders.UserAgent, "EnduLink/0.1.0 (Mobile Trail Navigation; https://github.com)")
                                        append(HttpHeaders.Accept, "image/png,image/jpeg,image/*;q=0.9,*/*;q=0.8")
                                    }
                                }
                                if (resp.status == HttpStatusCode.OK) {
                                    val bytes = resp.readBytes()
                                    if (bytes.size > 100) {
                                        TileDiskStorage.writeBytes(path, bytes)
                                        success = true
                                        break
                                    }
                                }
                            } catch (_: Exception) {
                                delay(300)
                            }
                        }

                        val (c, f) = synchronized(this@TileStore) {
                            if (success) completed++ else failed++
                            Pair(completed, failed)
                        }

                        if ((c + f) % 8 == 0 || (c + f) == total) {
                            onProgress(OfflineDownloadProgress(isDownloading = true, label = label, completed = c, total = total, failed = f))
                        }
                    }
                }
            }
            jobs.joinAll()
            if (!isCancelled && completed > 0 && packageKey != null) {
                markPackageDownloaded(packageKey)
            }
            onProgress(OfflineDownloadProgress(isDownloading = false, label = label, completed = completed, total = total, failed = failed))
        }
    }

    fun clearDiskCache() {
        synchronized(memoryCacheLock) { memoryCache.clear() }
        synchronized(bitmapCacheLock) { bitmapCache.clear() }
        _downloadedPackages.value = emptySet()
        TileDiskStorage.deleteDirectory(PlatformPaths.getTileCacheDir())
    }

    companion object {
        val shared by lazy { TileStore() }
    }
}

