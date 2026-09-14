package com.openchat.android.ubuntu

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

/**
 * Pure-JVM tar.gz plumbing for the Ubuntu userspace (no Android imports, so it
 * is unit-testable on the JVM and in CI):
 *
 *  - [write]: stream the rootfs into a .tar.gz backup (Export). Preserves the
 *    directory structure, POSIX permission bits and symbolic links; sockets,
 *    devices and fifos are skipped honestly (counted in [Stats]).
 *  - [extract]: hardened inverse (Import + install extraction). Rejects
 *    absolute and ".." entries (path traversal), restores symlinks and
 *    permission bits, streams with progress, auto-detects gzip vs plain tar.
 *  - [normalizeRoot]: accepts archives that wrap the rootfs in exactly one
 *    top-level directory and flattens them in place before validation.
 *
 * Everything here runs on the host filesystem; nothing chroots or executes.
 */
object UbuntuArchive {

    private const val BUF = 128 * 1024

    /** Honest counters for logging/verification after a write or extract. */
    data class Stats(
        val files: Int = 0,
        val dirs: Int = 0,
        val symlinks: Int = 0,
        val skippedSpecial: Int = 0,
        val bytes: Long = 0,
    )

    /**
     * Streams [rootfs] into [out] as a gzipped tar. [onProgress] reports
     * (bytesDone, bytesTotal) over regular-file content. Deterministic, sorted
     * entry order; long paths use POSIX/PAX headers so deep node_modules trees
     * never fail. Closes [out] when done.
     */
    fun write(
        rootfs: File,
        out: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Stats {
        val rootPath = rootfs.toPath()
        val total = rootfs.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        var done = 0L
        var files = 0
        var dirs = 0
        var symlinks = 0
        var skipped = 0

        val gzip = GzipCompressorOutputStream(out)
        val tar = TarArchiveOutputStream(BufferedOutputStream(gzip, BUF))
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

        fun visit(dir: File) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                val name = rootPath.relativize(child.toPath()).toString().replace(File.separatorChar, '/')
                if (name.isEmpty()) continue
                val path = child.toPath()
                val attrs = try {
                    Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: Exception) {
                    continue // unreadable entry — skip rather than corrupt the archive
                }
                when {
                    attrs.isSymbolicLink -> {
                        val e = TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
                        e.setLinkName(Files.readSymbolicLink(path).toString())
                        e.setModTime(child.lastModified())
                        e.setUserId(0)
                        e.setGroupId(0)
                        tar.putArchiveEntry(e)
                        tar.closeArchiveEntry()
                        symlinks++
                    }
                    attrs.isDirectory -> {
                        val e = TarArchiveEntry("$name/", TarArchiveEntry.LF_DIR)
                        e.setModTime(child.lastModified())
                        tar.putArchiveEntry(e)
                        tar.closeArchiveEntry()
                        dirs++
                        visit(child)
                    }
                    attrs.isRegularFile -> {
                        val e = TarArchiveEntry(name, TarArchiveEntry.LF_NORMAL)
                        e.setSize(child.length())
                        e.setModTime(child.lastModified())
                        e.setMode(permBits(path))
                        e.setUserId(0)
                        e.setGroupId(0)
                        tar.putArchiveEntry(e)
                        FileInputStream(child).use { ins -> ins.copyTo(tar) }
                        tar.closeArchiveEntry()
                        files++
                        done += child.length()
                        onProgress(done, total)
                    }
                    else -> skipped++ // sockets, devices, fifos — not exportable
                }
            }
        }

        try {
            // The root itself is not written as an entry — importers create it.
            visit(rootfs)
        } finally {
            try {
                tar.close()
            } catch (_: Exception) {
                // Stream already broken — the caller gets the original error.
            }
        }
        return Stats(files = files, dirs = dirs, symlinks = symlinks, skippedSpecial = skipped, bytes = done)
    }

    /**
     * Extracts a tar (gzip auto-detected) from [ins] into [targetDir], which is
     * created on demand.
     *
     * Security: entries with absolute names or ".." segments throw
     * [SecurityException]; symlinks are restored as links (never written
     * through); special entries are skipped. [onProgress] reports the number
     * of source (compressed) bytes consumed — it matches the file size the
     * user picked, so it can be divided by a known total for percent progress.
     */
    fun extract(
        ins: InputStream,
        targetDir: File,
        onProgress: (Long) -> Unit = {},
    ): Stats {
        targetDir.mkdirs()
        var files = 0
        var dirs = 0
        var symlinks = 0
        var skipped = 0
        var contentBytes = 0L

        // Sniff the gzip magic without mark/reset support (SAF streams lack it):
        // read the first two bytes, then push them back in front of the stream.
        val head = ByteArray(2)
        var n = 0
        while (n < 2) {
            val r = ins.read(head, n, 2 - n)
            if (r < 0) break
            n += r
        }
        val isGzip = n == 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
        val source: InputStream = if (n == 0) ins else SequenceInputStream(ByteArrayInputStream(head, 0, n), ins)
        val counting = CountingInputStream(source) { read -> onProgress(read) }
        val decompressed: InputStream = if (isGzip) GzipCompressorInputStream(counting) else counting

        TarArchiveInputStream(BufferedInputStream(decompressed, BUF)).use { tin ->
            while (true) {
                val entry = tin.nextTarEntry ?: break
                var name = entry.name
                if (name == "." || name == "./") continue
                if (name.startsWith("./")) name = name.substring(2)
                if (name.startsWith("/")) {
                    throw SecurityException("Absolute path entry in archive: ${entry.name}")
                }
                if (name.split('/').contains("..")) {
                    throw SecurityException("Path traversal entry in archive: ${entry.name}")
                }
                if (name.isEmpty()) continue

                val outFile = File(targetDir, name)
                when {
                    entry.isDirectory -> {
                        if (outFile.exists() && !outFile.isDirectory) outFile.delete()
                        outFile.mkdirs()
                        dirs++
                    }
                    entry.isSymbolicLink -> {
                        outFile.parentFile?.mkdirs()
                        if (outFile.isDirectory) outFile.deleteRecursively() else outFile.delete()
                        try {
                            Files.createSymbolicLink(outFile.toPath(), Paths.get(entry.linkName))
                        } catch (e: Exception) {
                            throw IOException(
                                "symlink failed for ${entry.name} → ${entry.linkName}: ${e.message}",
                            )
                        }
                        symlinks++
                    }
                    entry.isLink -> { // hard link: materialize the target's content
                        outFile.parentFile?.mkdirs()
                        val src = File(targetDir, entry.linkName.trimStart('/', '.'))
                        if (src.isFile) {
                            FileInputStream(src).use { i ->
                                FileOutputStream(outFile).use { o -> i.copyTo(o) }
                            }
                            applyMode(outFile.toPath(), entry.mode.toLong())
                        } else {
                            FileOutputStream(outFile).use { o -> tin.copyTo(o) }
                        }
                        files++
                    }
                    else -> {
                        // Regular file = linkFlag '0' or NUL (both occur in real
                        // tarballs). Everything else — fifos, char/block devices,
                        // sparse entries — is skipped and counted honestly.
                        val flag = entry.linkFlag
                        if (flag == TarArchiveEntry.LF_NORMAL || flag == 0.toByte()) {
                            if (outFile.isDirectory) outFile.deleteRecursively()
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { o -> tin.copyTo(o) }
                            applyMode(outFile.toPath(), entry.mode.toLong())
                            files++
                            contentBytes += entry.size.coerceAtLeast(0)
                        } else {
                            skipped++
                        }
                    }
                }
            }
        }
        return Stats(files = files, dirs = dirs, symlinks = symlinks, skippedSpecial = skipped, bytes = contentBytes)
    }

    /**
     * Import validation: the extracted tree must contain `bin/bash`. Archives
     * that wrap the rootfs in exactly one top-level directory are flattened
     * in place. Returns how many levels were flattened (0 when already flat).
     *
     * @throws IOException when no usable rootfs is found (wrong archive).
     */
    fun normalizeRoot(staging: File): Int {
        if (File(staging, "bin/bash").isFile) return 0
        val children = staging.listFiles().orEmpty()
        val wrapper = children.singleOrNull { it.isDirectory }
        if (children.size == 1 && wrapper != null && File(wrapper, "bin/bash").isFile) {
            for (child in wrapper.listFiles().orEmpty()) {
                Files.move(
                    child.toPath(),
                    File(staging, child.name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            wrapper.delete()
            if (File(staging, "bin/bash").isFile) return 1
        }
        throw IOException(
            "The archive does not contain a usable Ubuntu rootfs (bin/bash missing) — " +
                "export the userspace from OpenChat or pick a full rootfs tarball",
        )
    }

    /** SHA-256 of a stream without loading it into memory (integrity log line). */
    fun sha256(ins: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ internals

    /** Permission bits of [path] (defaults to 0644 when POSIX attrs fail). */
    private fun permBits(path: Path): Int {
        val perms = try {
            Files.getPosixFilePermissions(path)
        } catch (_: Exception) {
            return 0b110_100_100
        }
        var bits = 0
        for (p in perms) {
            bits = bits or when (p) {
                PosixFilePermission.OWNER_READ -> 0b100_000_000
                PosixFilePermission.OWNER_WRITE -> 0b010_000_000
                PosixFilePermission.OWNER_EXECUTE -> 0b001_000_000
                PosixFilePermission.GROUP_READ -> 0b000_100_000
                PosixFilePermission.GROUP_WRITE -> 0b000_010_000
                PosixFilePermission.GROUP_EXECUTE -> 0b000_001_000
                PosixFilePermission.OTHERS_READ -> 0b000_000_100
                PosixFilePermission.OTHERS_WRITE -> 0b000_000_010
                PosixFilePermission.OTHERS_EXECUTE -> 0b000_000_001
            }
        }
        return bits
    }

    /** Applies tar permission bits to an extracted path (best-effort). */
    private fun applyMode(path: Path, rawMode: Long) {
        val bits = rawMode.toInt() and 0b111_111_111
        try {
            Files.setPosixFilePermissions(path, bitsToPermSet(bits))
        } catch (_: Exception) {
            // Best effort — a missed chmod never corrupts the tree.
        }
    }

    private fun bitsToPermSet(bits: Int): Set<PosixFilePermission> {
        val out = HashSet<PosixFilePermission>()
        if (bits and 0b100_000_000 != 0) out.add(PosixFilePermission.OWNER_READ)
        if (bits and 0b010_000_000 != 0) out.add(PosixFilePermission.OWNER_WRITE)
        if (bits and 0b001_000_000 != 0) out.add(PosixFilePermission.OWNER_EXECUTE)
        if (bits and 0b000_100_000 != 0) out.add(PosixFilePermission.GROUP_READ)
        if (bits and 0b000_010_000 != 0) out.add(PosixFilePermission.GROUP_WRITE)
        if (bits and 0b000_001_000 != 0) out.add(PosixFilePermission.GROUP_EXECUTE)
        if (bits and 0b000_000_100 != 0) out.add(PosixFilePermission.OTHERS_READ)
        if (bits and 0b000_000_010 != 0) out.add(PosixFilePermission.OTHERS_WRITE)
        if (bits and 0b000_000_001 != 0) out.add(PosixFilePermission.OTHERS_EXECUTE)
        return out
    }

    /** Minimal counting wrapper used for source-bytes progress. */
    private class CountingInputStream(
        private val delegate: InputStream,
        private val onUpdate: (Long) -> Unit,
    ) : InputStream() {
        private var count = 0L
        private var lastReported = 0L

        private fun tick(n: Int) {
            if (n > 0) {
                count += n
                if (count - lastReported >= 256 * 1024) { // throttle to every 256 KiB
                    lastReported = count
                    onUpdate(count)
                }
            }
        }

        override fun read(): Int {
            val r = delegate.read()
            if (r >= 0) tick(1)
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            tick(n)
            return n
        }

        override fun skip(n: Long): Long {
            val s = delegate.skip(n)
            tick(s.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            return s
        }

        override fun available(): Int = delegate.available()
        override fun close() = delegate.close()
    }
}
