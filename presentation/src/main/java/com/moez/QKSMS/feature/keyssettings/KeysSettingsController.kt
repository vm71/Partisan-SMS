package com.moez.QKSMS.feature.keyssettings

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.qrcode.QRCodeWriter
import com.jakewharton.rxbinding2.view.clicks
import com.moez.QKSMS.R
import com.moez.QKSMS.common.base.QkController
import com.moez.QKSMS.common.util.extensions.animateLayoutChanges
import com.moez.QKSMS.common.widget.PreferenceView
import com.moez.QKSMS.injection.appComponent
import com.moez.QKSMS.interactor.SetDeleteMessagesAfter
import com.moez.QKSMS.interactor.SetEncodingScheme
import com.moez.QKSMS.interactor.SetEncryptionEnabled
import com.moez.QKSMS.interactor.SetEncryptionKey
import com.moez.QKSMS.repository.ConversationRepository
import com.moez.QKSMS.util.Preferences
import io.reactivex.Observable
import kotlinx.android.synthetic.main.settings_keys_activity.*
import kotlinx.android.synthetic.main.settings_keys_activity.preferences
import kotlinx.android.synthetic.main.settings_switch_widget.view.*
import javax.crypto.KeyGenerator
import javax.inject.Inject

class KeysSettingsController : QkController<KeysSettingsView, KeysSettingsState, KeysSettingsPresenter>(), KeysSettingsView, DialogInterface.OnClickListener {

    @Inject lateinit var setEncryptionKey: SetEncryptionKey
    @Inject lateinit var setEncryptionEnabled: SetEncryptionEnabled
    @Inject lateinit var setEncodingScheme: SetEncodingScheme
    @Inject lateinit var context: Context
    @Inject lateinit var prefs: Preferences
    @Inject lateinit var conversationsRepo: ConversationRepository
    @Inject lateinit var qrCodeWriter: QRCodeWriter
    @Inject lateinit var setDeleteMessagesAfter: SetDeleteMessagesAfter

    @Inject override lateinit var presenter: KeysSettingsPresenter

    private lateinit var generatedKey: String
    private lateinit var deleteAfterLabels: Array<String>
    private lateinit var encodingSchemes: Array<String>
    private lateinit var schemesListAdapter: KeysSettingsListAdapter
    private var threadId = -1L
    private var initialState = KeysSettingsState()
    private var newState = KeysSettingsState()

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
        layoutRes = R.layout.settings_keys_activity
    }

    override fun preferenceClicks(): Observable<PreferenceView> = (0 until preferences.childCount)
        .map { index -> preferences.getChildAt(index) }
        .mapNotNull { view -> view as? PreferenceView }
        .map { preference -> preference.clicks().map { preference } }
        .let { preferences -> Observable.merge(preferences) }

    override fun render(state: KeysSettingsState) {
        encryptionKeyCategory.text =
            if(state.isConversation) context.getText(R.string.settings_global_encryption_key_title)
            else context.getText(R.string.settings_encryption_key_title)

        enableKey.checkbox.isChecked = if(!state.isConversation) state.key.isNotBlank() else state.keyEnabled

        keyInputGroup.visibility = if(state.keySettingsIsShown) View.VISIBLE else View.GONE
        scanQr.alpha = if(state.keyEnabled) 1f else 0.5f
        scanQr.isClickable = state.keyEnabled
        generateKey.alpha = if(state.keyEnabled) 1f else 0.5f
        generateKey.isClickable = state.keyEnabled

        resetKey.alpha = if(state.key.isNotBlank()) 1f else 0.5f
        resetKey.isClickable = state.key.isNotBlank()
        resetKeyCheck.visibility = if(state.resetCheckIsShown) View.VISIBLE else View.GONE

        settings_deletion.visibility = if(state.isConversation) View.VISIBLE else View.GONE
        settings_delete_encrypted_after.progress = state.deleteEncryptedAfter
        settings_delete_encrypted_after_pref.summary = deleteAfterLabels[state.deleteEncryptedAfter]
        settings_delete_received_after.progress = state.deleteReceivedAfter
        settings_delete_received_after_pref.summary = deleteAfterLabels[state.deleteReceivedAfter]
        settings_delete_sent_after.progress = state.deleteSentAfter
        settings_delete_sent_after_pref.summary = deleteAfterLabels[state.deleteSentAfter]
    }

    override fun onViewCreated() {
        super.onViewCreated()
        threadId = requireActivity().intent.getLongExtra("threadId", -1)
        val currentScheme = if(threadId == -1L) prefs.encodingScheme.get()
        else conversationsRepo.getConversation(threadId)?.encodingSchemeId ?: 0
        encodingSchemes = context.resources.getStringArray(R.array.encoding_scheme_labels)
        deleteAfterLabels = context.resources.getStringArray(R.array.delete_message_after_labels)
        schemesListAdapter = KeysSettingsListAdapter(encodingSchemes, currentScheme, this::selectEncodingScheme)

        if(threadId == -1L) {
            newState = newState.copy(
                keyEnabled = prefs.globalEncryptionKey.get().isNotBlank(),
                key = prefs.globalEncryptionKey.get(),
                encodingScheme = prefs.encodingScheme.get(),
                isConversation = false
            )
            initialState = newState
            presenter.setGlobalParameters(
                keyEnabled = newState.keyEnabled,
                key = newState.key,
                encodingScheme = newState.encodingScheme
            )
            schemesListAdapter.setSelected(prefs.encodingScheme.get())
        } else {
            val conversation = conversationsRepo.getConversation(threadId)
            newState = newState.copy(
                keyEnabled = conversation?.encryptionEnabled ?: false,
                key = conversation?.encryptionKey ?: "",
                encodingScheme = conversation?.encodingSchemeId ?: prefs.encodingScheme.get(),
                deleteEncryptedAfter = conversation?.deleteEncryptedAfter ?: 0,
                deleteReceivedAfter = conversation?.deleteReceivedAfter ?: 0,
                deleteSentAfter = conversation?.deleteSentAfter ?: 0,
                isConversation = true
            )
            initialState = newState
            presenter.setConversationParameters(
                keyEnabled = newState.keyEnabled,
                key = newState.key,
                encodingScheme = newState.encodingScheme,
                deleteEncryptedAfter = newState.deleteEncryptedAfter,
                deleteReceivedAfter = newState.deleteReceivedAfter,
                deleteSentAfter = newState.deleteSentAfter,
            )
            schemesListAdapter.setSelected(conversation?.encodingSchemeId ?: 0)
        }


        preferences.postDelayed( { preferences?.animateLayoutChanges = true }, 100)
        encodingSchemesRecycler.layoutManager = LinearLayoutManager(context)
        encodingSchemesRecycler.adapter = schemesListAdapter

        settings_delete_encrypted_after.max = deleteAfterLabels.lastIndex
        settings_delete_received_after.max = deleteAfterLabels.lastIndex
        settings_delete_sent_after.max = deleteAfterLabels.lastIndex

        settings_delete_encrypted_after.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if(fromUser) {
                        presenter.setDeleteEncryptedAfter(value)
                        setDeleteEncryptedAfter(value)
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            }
        )
        settings_delete_received_after.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if(fromUser) {
                        presenter.setDeleteReceivedAfter(value)
                        setDeleteReceivedAfter(value)
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            }
        )
        settings_delete_sent_after.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if(fromUser) {
                        presenter.setDeleteSentAfter(value)
                        setDeleteSentAfter(value)
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            }
        )
        field.doOnTextChanged { text, _, _, _ ->
            if(validate(text.toString())) {
                newState = newState.copy(key = text.toString())
                Toast.makeText(context, R.string.settings_key_has_been_set, Toast.LENGTH_SHORT).show()
            } else field.error = context.getText(R.string.settings_bad_key)
        }
        copyKey.setOnClickListener {
            copyKey()
        }
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.settings_category_hidden)
        showBackButton(true)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.keysettings, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.confirm -> saveChanges()
        }
        return true
    }

    override fun handleBack(): Boolean {
        return if(newState == initialState) super.handleBack()
        else {
            AlertDialog.Builder(this.activity)
                .setMessage(R.string.settings_exit_with_no_changes)
                .setNegativeButton(R.string.rate_dismiss, this)
                .setPositiveButton(R.string.button_save, this)
                .create()
                .show()
            true
        }
    }

    override fun onClick(dialog: DialogInterface?, button: Int) {
        if(button == AlertDialog.BUTTON_NEGATIVE) {
            newState = initialState
            requireActivity().finish()
        }
        if(button == AlertDialog.BUTTON_POSITIVE) {
            saveChanges()
            requireActivity().finish()
        }
    }

    override fun generateKey() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        field.text.clear()
        generatedKey = Base64.encodeToString(keyGen.generateKey().encoded, Base64.NO_WRAP)
        field.text.insert(0,generatedKey)
        //generate QR
        val matrix = qrCodeWriter.encode(generatedKey,BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for(i in 0 until matrix.width)
            for(j in 0 until matrix.height) {
                bitmap.setPixel(i,j, if(matrix[i,j]) Color.BLACK else Color.WHITE)
            }
        qrCodeImage.setImageBitmap(bitmap)
        newState = newState.copy(key = generatedKey)
        presenter.setKey(newState.key)
    }

    override fun selectEncodingScheme(schemeId: Int) {
        newState = newState.copy(encodingScheme = schemeId)
    }

    override fun copyKey() {
        field.apply {
            if(copyToClipboard()) {
                selectAll()
                Toast.makeText(context, R.string.encryption_key_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun scanQrCode() {
        val i = IntentIntegrator(this.themedActivity)
            .setBeepEnabled(false)
            .setOrientationLocked(true)
            .setBarcodeImageEnabled(true)
            .createScanIntent()
        startActivityForResult(i, 1)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val qrResult = IntentIntegrator.parseActivityResult(resultCode, data)
        if(qrResult != null && qrResult.contents != null) {
            if(validate(qrResult.contents)) {
                generatedKey = qrResult.contents
                newState = newState.copy(key = qrResult.contents, keyEnabled = true)
                Toast.makeText(context,"${context.getText(R.string.settings_key_has_been_set)}",Toast.LENGTH_LONG).show()
                if(newState.isConversation) {
                    presenter.setConversationParameters(
                        keyEnabled = true,
                        key = generatedKey,
                        encodingScheme = newState.encodingScheme,
                        deleteEncryptedAfter = newState.deleteEncryptedAfter,
                        deleteReceivedAfter = newState.deleteReceivedAfter,
                        deleteSentAfter = newState.deleteSentAfter
                    )
                } else {
                    presenter.setGlobalParameters(
                        keyEnabled = true,
                        key = generatedKey,
                        encodingScheme = newState.encodingScheme
                    )
                }
            }
            else Toast.makeText(context, context.getText(R.string.settings_bad_key), Toast.LENGTH_SHORT).show()
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    override fun resetKey() {
        newState = newState.copy(key = "", keyEnabled = false)
        Toast.makeText(context, context.getText(R.string.settings_key_reset), Toast.LENGTH_SHORT).show()
    }

    override fun keyEnabled(enabled: Boolean) {
        newState = newState.copy(keyEnabled = enabled)
    }

    private fun EditText.copyToClipboard(): Boolean {
        val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java)
        return if (text.isNotBlank() && clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.conversation_encryption_key_title), text))
            true
        } else false
    }

    override fun setDeleteEncryptedAfter(delay: Int) {
        newState = newState.copy(deleteEncryptedAfter = delay)
    }

    override fun setDeleteReceivedAfter(delay: Int) {
        newState = newState.copy(deleteReceivedAfter = delay)
    }

    override fun setDeleteSentAfter(delay: Int) {
        newState = newState.copy(deleteSentAfter = delay)
    }

    private fun validate(text: String): Boolean {
        return try {
            if (text.isEmpty()) return true
            val data = Base64.decode(text, Base64.DEFAULT)
            data.size == 16 || data.size == 24 || data.size == 32
        } catch (ignored: IllegalArgumentException) {
            false
        }
    }

    private fun saveChanges() {
        if(newState.isConversation) {
            setDeleteMessagesAfter.execute(SetDeleteMessagesAfter.Params(threadId, SetDeleteMessagesAfter.MessageType.ENCRYPTED, newState.deleteEncryptedAfter))
            setDeleteMessagesAfter.execute(SetDeleteMessagesAfter.Params(threadId, SetDeleteMessagesAfter.MessageType.RECEIVED, newState.deleteReceivedAfter))
            setDeleteMessagesAfter.execute(SetDeleteMessagesAfter.Params(threadId, SetDeleteMessagesAfter.MessageType.SENT, newState.deleteSentAfter))
            setEncryptionEnabled.execute(SetEncryptionEnabled.Params(threadId, newState.keyEnabled))
            setEncryptionKey.execute(SetEncryptionKey.Params(threadId, newState.key))
            setEncodingScheme.execute(SetEncodingScheme.Params(threadId, newState.encodingScheme))
        } else {
            prefs.globalEncryptionKey.set(newState.key)
            prefs.encodingScheme.set(newState.encodingScheme)
        }
        initialState = newState
    }

}