package com.example.myapplication.data.reader.content

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

/** Private file operations with no API26 types in Android signatures or class initialization. */
internal object Api24PrivateFiles {
    private val androidRuntime: Boolean get() = System.getProperty("java.vm.name") == "Dalvik"
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** Keep the operation failure, run every cleanup, and preserve later failures as suppressed. */
    internal fun <T> withCleanup(action: () -> T, vararg cleanups: () -> Unit): T {
        var primary: Throwable? = null
        var actionFailed = false
        try {
            return action()
        } catch (error: Throwable) {
            actionFailed = true
            primary = error
            throw error
        } finally {
            for (cleanup in cleanups) {
                try { cleanup() } catch (error: Throwable) {
                    val existing = primary
                    if (existing == null) primary = error
                    else if (existing !== error) existing.addSuppressed(error)
                }
            }
            // With no operation failure, the first cleanup failure remains observable to the caller.
            if (!actionFailed) primary?.let { throw it }
        }
    }

    fun normalized(file: File): File = file.absoluteFile.normalize()
    fun key(file: File): String = normalized(file).path.let { if (windows) it.lowercase(Locale.ROOT) else it }
    fun within(root: File, file: File): Boolean = key(file) == key(root) || key(file).startsWith(key(root) + File.separator)

    fun checkLinks(file: File) {
        if (androidRuntime) AndroidFiles.checkLinks(normalized(file)) else HostFiles.checkLinks(normalized(file))
    }

    fun exists(file: File): Boolean = if (androidRuntime) AndroidFiles.stat(file) != null else HostFiles.exists(file)
    fun regular(file: File): Boolean = if (androidRuntime) {
        AndroidFiles.stat(file)?.let { OsConstants.S_ISREG(it.st_mode) } == true
    } else HostFiles.regular(file)

    fun prepareDirectory(file: File) {
        checkLinks(file)
        if (!exists(file)) file.mkdirs() // Recheck a concurrent creator rather than relying on mkdirs' return value.
        checkLinks(file)
        require(file.isDirectory) { "store: non-directory private path" }
        if (androidRuntime) Os.chmod(file.absolutePath, 0x1c0) else HostFiles.privateDirectory(file)
    }

    fun input(file: File): InputStream {
        checkLinks(file)
        return if (androidRuntime) AndroidFiles.input(file) else HostFiles.input(file)
    }

    fun writeForced(file: File, bytes: ByteArray) {
        checkLinks(file)
        if (androidRuntime) {
            val descriptor = AndroidFiles.openChecked(file, OsConstants.O_WRONLY, create = false)
            withCleanup({
                // Opening never truncates an unverified pathname; only the verified descriptor is changed.
                Os.fchmod(descriptor, 0x180)
                Os.ftruncate(descriptor, 0)
                FileOutputStream(descriptor).use { output -> output.write(bytes); output.fd.sync() }
            }, { closeDescriptor(descriptor) })
        } else {
            HostFiles.permissions(file, "rw-------")
            HostFiles.channel(file, create = false).use { channel ->
                channel.truncate(0)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        }
    }

    class LockHandle internal constructor(val channel: FileChannel, private val dispose: () -> Unit) : Closeable {
        override fun close() = dispose()
    }

    fun openLock(file: File): LockHandle {
        checkLinks(file)
        if (!androidRuntime) {
            val channel = HostFiles.channel(file, create = true)
            return LockHandle(channel) { channel.close() }
        }
        val descriptor = AndroidFiles.openChecked(file, OsConstants.O_WRONLY, create = true)
        try {
            val stream = FileOutputStream(descriptor)
            val channel = stream.channel
            return LockHandle(channel) {
                withCleanup({ channel.close() }, { stream.close() }, { closeDescriptor(descriptor) })
            }
        } catch (error: Throwable) {
            return withCleanup({ throw error }, { closeDescriptor(descriptor) })
        }
    }

    /** Same-directory rename is the commit point; unsupported atomic moves never fall back to copy. */
    fun atomicMove(source: File, target: File, replace: Boolean) {
        checkLinks(source); checkLinks(target)
        require(source.parentFile?.canonicalFile == target.parentFile?.canonicalFile) { "store: atomic move requires one private directory" }
        if (!replace) require(!exists(target)) { "store: destination already exists" }
        if (androidRuntime) Os.rename(source.absolutePath, target.absolutePath)
        else HostFiles.atomicMove(source, target, replace)
    }

    private fun closeDescriptor(descriptor: FileDescriptor) {
        // Android's public FileDescriptor stream constructors borrow the descriptor and do not close it.
        if (descriptor.valid()) Os.close(descriptor)
    }

    private object AndroidFiles {
        fun stat(file: File): StructStat? = try { Os.lstat(file.absolutePath) } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) null else throw error
        }

        private fun ordinary(stat: StructStat) {
            require(OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISDIR(stat.st_mode)) {
                "store: links/reparse/special paths are forbidden"
            }
            if (OsConstants.S_ISREG(stat.st_mode)) require(stat.st_nlink == 1L) { "store: hard-linked files are forbidden" }
        }

        fun checkLinks(file: File) {
            var cursor: File? = file
            while (cursor != null) {
                stat(cursor)?.let { ordinary(it) }
                require(key(cursor.canonicalFile) == key(cursor)) { "store: resolved path differs (link/reparse path)" }
                cursor = cursor.parentFile
            }
        }

        fun openChecked(file: File, flags: Int, create: Boolean): FileDescriptor {
            val expected = stat(file)
            expected?.let { ordinary(it); require(OsConstants.S_ISREG(it.st_mode)) { "store.input: non-regular file" } }
            require(create || expected != null) { "store.input: missing/non-regular file" }
            val descriptor = Os.open(file.absolutePath, flags or OsConstants.O_NOFOLLOW or
                (if (create) OsConstants.O_CREAT else 0), 0x180)
            try {
                val opened = Os.fstat(descriptor)
                ordinary(opened)
                require(OsConstants.S_ISREG(opened.st_mode)) { "store.input: non-regular descriptor" }
                checkLinks(normalized(file))
                val named = requireNotNull(stat(file)) { "store.input: pathname disappeared" }
                require(opened.st_dev == named.st_dev && opened.st_ino == named.st_ino &&
                    (expected == null || opened.st_dev == expected.st_dev && opened.st_ino == expected.st_ino)) {
                    "store.input: file identity changed while opening"
                }
                return descriptor
            } catch (error: Throwable) {
                return withCleanup({ throw error }, { closeDescriptor(descriptor) })
            }
        }

        fun input(file: File): InputStream {
            val descriptor = openChecked(file, OsConstants.O_RDONLY, create = false)
            try {
                return object : FilterInputStream(FileInputStream(descriptor)) {
                    private var disposed = false
                    @Synchronized override fun close() {
                        if (disposed) return
                        disposed = true
                        withCleanup({ super.close() }, { closeDescriptor(descriptor) })
                    }
                }
            } catch (error: Throwable) {
                return withCleanup({ throw error }, { closeDescriptor(descriptor) })
            }
        }
    }

    /** This backend is reached only on host JVMs. Reflection avoids linking NIO on API24 ART. */
    private object HostFiles {
        private val pathType = Class.forName("java.nio.file.Path")
        private val filesType = Class.forName("java.nio.file.Files")
        private val linkType = Class.forName("java.nio.file.LinkOption")
        private val openType = Class.forName("java.nio.file.OpenOption")
        private val copyType = Class.forName("java.nio.file.CopyOption")
        private val attributesType = Class.forName("java.nio.file.attribute.BasicFileAttributes")
        private val linkArrayType = ReflectArray.newInstance(linkType, 0).javaClass
        private val noFollow = option("java.nio.file.LinkOption", "NOFOLLOW_LINKS")

        private fun option(type: String, name: String): Any = Class.forName(type).getField(name).get(null)
        private fun options(type: Class<*>, values: List<Any>): Any = ReflectArray.newInstance(type, values.size).also { array ->
            values.forEachIndexed { index, value -> ReflectArray.set(array, index, value) }
        }
        private fun invoke(owner: Class<*>, receiver: Any?, name: String, types: Array<Class<*>>, vararg arguments: Any): Any? =
            try { owner.getMethod(name, *types).invoke(receiver, *arguments) } catch (error: InvocationTargetException) { throw error.targetException }
        private fun path(file: File): Any = requireNotNull(invoke(File::class.java, file, "toPath", emptyArray()))
        private fun files(name: String, types: Array<Class<*>>, vararg arguments: Any): Any? = invoke(filesType, null, name, types, *arguments)
        private fun links(): Any = options(linkType, listOf(noFollow))
        private fun views(path: Any): Set<*> {
            val system = requireNotNull(invoke(pathType, path, "getFileSystem", emptyArray()))
            return invoke(Class.forName("java.nio.file.FileSystem"), system, "supportedFileAttributeViews", emptyArray()) as Set<*>
        }

        fun exists(file: File): Boolean = files("exists", arrayOf(pathType, linkArrayType), path(file), links()) as Boolean
        fun regular(file: File): Boolean = files("isRegularFile", arrayOf(pathType, linkArrayType), path(file), links()) as Boolean
        fun checkLinks(file: File) {
            var cursor: File? = file
            while (cursor != null) {
                val currentPath = path(cursor)
                if (files("exists", arrayOf(pathType, linkArrayType), currentPath, links()) as Boolean) {
                    val attributes = requireNotNull(files("readAttributes", arrayOf(pathType, Class::class.java, linkArrayType),
                        currentPath, attributesType, links()))
                    fun flag(name: String) = invoke(attributesType, attributes, name, emptyArray()) as Boolean
                    require(!flag("isSymbolicLink") && !flag("isOther") &&
                        !(files("isSymbolicLink", arrayOf(pathType), currentPath) as Boolean)) { "store: links/reparse/special paths are forbidden" }
                    val resolved = requireNotNull(invoke(pathType, currentPath, "toRealPath", arrayOf(linkArrayType), options(linkType, emptyList())))
                    require(key(File(resolved.toString())) == key(cursor)) { "store: resolved path differs (link/reparse path)" }
                    if (flag("isRegularFile") && "unix" in views(currentPath)) {
                        val count = files("getAttribute", arrayOf(pathType, String::class.java, linkArrayType), currentPath, "unix:nlink", links()) as Number
                        require(count.toLong() == 1L) { "store: hard-linked files are forbidden" }
                    }
                }
                cursor = cursor.parentFile
            }
        }

        fun privateDirectory(file: File) = permissions(file, "rwx------")
        fun permissions(file: File, mode: String) {
            val currentPath = path(file)
            if ("posix" in views(currentPath)) {
                val permissions = requireNotNull(invoke(Class.forName("java.nio.file.attribute.PosixFilePermissions"), null,
                    "fromString", arrayOf(String::class.java), mode))
                files("setPosixFilePermissions", arrayOf(pathType, Set::class.java), currentPath, permissions)
            }
        }
        fun input(file: File): InputStream {
            val array = options(openType, listOf(noFollow))
            return files("newInputStream", arrayOf(pathType, array.javaClass), path(file), array) as InputStream
        }
        fun channel(file: File, create: Boolean): FileChannel {
            val values = mutableListOf(noFollow, option("java.nio.file.StandardOpenOption", "WRITE"))
            if (create) values += option("java.nio.file.StandardOpenOption", "CREATE")
            val array = options(openType, values)
            return invoke(FileChannel::class.java, null, "open", arrayOf(pathType, array.javaClass), path(file), array) as FileChannel
        }
        fun atomicMove(source: File, target: File, replace: Boolean) {
            val values = mutableListOf(option("java.nio.file.StandardCopyOption", "ATOMIC_MOVE"))
            if (replace) values += option("java.nio.file.StandardCopyOption", "REPLACE_EXISTING")
            val array = options(copyType, values)
            files("move", arrayOf(pathType, pathType, array.javaClass), path(source), path(target), array)
        }
    }
}
