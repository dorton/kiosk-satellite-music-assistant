package net.markandrew.kiosk.music.receiver

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.sendspindroid.sendspin.crypto.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound encrypted storage. Broken/missing keys fail closed; never regenerate over data. */
class ReceiverStore(context: Context) : TrustStore, PairingConfigStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "musicassistantplugin-receiver-v1.enc"))
    private val alias = "musicassistantplugin-receiver-v1"
    private val key: SecretKey
    private var data: JSONObject

    init {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
        key = ks.getKey(alias, null) as SecretKey
        data = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val bytes = file.readFully()
            check(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } else {
            JSONObject().put("identity", ClientIdentity.encodeForStorage(ClientIdentity.generate()))
                .put("pairing", encode(ByteArray(32).also { SecureRandom().nextBytes(it) }))
                .put("records", JSONArray()).put("unpaired", false)
        }
        check(ClientIdentity.fromStoredKey(data.getString("identity")) != null)
        check(decode(data.getString("pairing")).size == 32)
        persist(data)
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    private fun persist(next: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.iv + cipher.doFinal(next.toString().toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try { out.write(encrypted); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
        data = next
    }

    @Synchronized private fun change(edit: (JSONObject) -> Unit) {
        val next = JSONObject(data.toString())
        edit(next)
        persist(next)
    }

    @Synchronized fun identity(): ClientIdentity =
        checkNotNull(ClientIdentity.fromStoredKey(data.getString("identity")))
    @Synchronized fun number(name: String): Int = data.optInt(name)
    @Synchronized fun text(name: String): String = data.optString(name, "")
    fun setText(name: String, value: String) = change { it.put(name, value) }
    fun setNumber(name: String, value: Int) = change { it.put(name, value) }
    @Synchronized fun musicVolume(endpoint: String): Int? {
        if (data.optString("musicVolumeEndpoint") != endpoint || data.optString("musicVolumeServer").isEmpty()) return null
        val value = data.optInt("musicVolume", -1)
        return value.takeIf { it in 0..100 }
    }
    fun saveMusicVolume(server: String, endpoint: String, level: Int) = change {
        require(server.isNotEmpty() && level in 0..100)
        it.put("musicVolumeServer", server).put("musicVolumeEndpoint", endpoint).put("musicVolume", level)
    }
    override val storageIsEncrypted = true

    @Synchronized override fun listRecords(): List<PskRecord> {
        val records = data.getJSONArray("records")
        return (0 until records.length()).map { i ->
            val item = records.getJSONObject(i)
            PskRecord(item.getString("id"), decode(item.getString("psk")),
                item.getString("server"), item.getBoolean("used"))
        }
    }
    @Synchronized override fun findByPskId(pskId: String) = listRecords().find { it.pskId == pskId }
    @Synchronized override fun candidates() = listRecords().map { it.toPsk() } + SentinelPsk.psk

    @Synchronized override fun addRecord(psk: ByteArray, serverId: String?): TrustStore.AddRecordResult {
        if (psk.size != 32 || serverId.isNullOrBlank()) return TrustStore.AddRecordResult.Invalid
        val id = PskId.derive(psk)
        if (candidates().any { it.pskId == id } || load().pairingPskId == id)
            return TrustStore.AddRecordResult.AlreadyExists
        val record = PskRecord(id, psk, serverId)
        return try {
            writeRecords(listRecords().filter { it.serverId != serverId } + record)
            TrustStore.AddRecordResult.Ok(record)
        } catch (_: Exception) { TrustStore.AddRecordResult.StorageFailed }
    }
    private fun writeRecords(records: List<PskRecord>) = change { next ->
        next.put("records", JSONArray().also { array -> records.forEach { r ->
            array.put(JSONObject().put("id", r.pskId).put("psk", encode(r.psk))
                .put("server", r.serverId).put("used", r.used))
        } })
    }
    @Synchronized override fun removeRecord(pskId: String): Boolean {
        if (findByPskId(pskId) == null) return false
        writeRecords(listRecords().filter { it.pskId != pskId }); return true
    }
    @Synchronized override fun markUsed(pskId: String) {
        val records = listRecords()
        if (records.any { it.pskId == pskId && !it.used })
            writeRecords(records.map { if (it.pskId == pskId) it.withUsed(true) else it })
    }
    @Synchronized override fun load() = PairingConfig(decode(data.getString("pairing")),
        pairingPskEnabled = true, unpairedAccessEnabled = false,
        dynamicPairingCodeEnabled = false, recordModePskId = "")
    override fun setEnabled(enabled: Boolean): Boolean = enabled
    override fun setRecordModePskId(pskId: String): Boolean = false
    @Synchronized override fun setUnpairedAccess(enabled: Boolean): Boolean = !enabled
    @Synchronized override fun rotatePairingPsk(newPsk: ByteArray, claimedPskIds: Set<String>): PairingConfigStore.RotateResult {
        if (newPsk.size != 32) return PairingConfigStore.RotateResult.Invalid
        if (PskId.derive(newPsk) in claimedPskIds) return PairingConfigStore.RotateResult.AlreadyExists
        return try {
            change { it.put("pairing", encode(newPsk)) }; PairingConfigStore.RotateResult.Ok
        } catch (_: Exception) { PairingConfigStore.RotateResult.StorageFailed }
    }
}
