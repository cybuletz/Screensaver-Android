package com.photostreamr.security

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.example.screensaver.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PasscodeDialog : DialogFragment() {

    @Inject
    lateinit var authManager: AppAuthManager

    private var passcodeListener: PasscodeDialogListener? = null
    private var dismissListener: (() -> Unit)? = null
    private var callback: PasscodeDialogCallback? = null

    private lateinit var passcodeInput: TextInputEditText
    private lateinit var errorText: TextView
    private lateinit var messageText: TextView
    private var mode: Mode = Mode.VERIFY
    private val handler = Handler(Looper.getMainLooper())
    private var lockoutRunnable: Runnable? = null
    private var isProcessingInput = false

    enum class Mode {
        VERIFY,
        SET_NEW,
        CONFIRM_NEW
    }

    sealed class DialogState {
        object Input : DialogState()
        data class Lockout(val remainingTime: Long) : DialogState()
        object Error : DialogState()
    }

    interface PasscodeDialogCallback {
        fun onPasscodeConfirmed(passcode: String)
        fun onError(message: String)
        fun onDismiss()
    }

    private var currentState: DialogState = DialogState.Input
        private set

    companion object {
        private const val TAG = "PasscodeDialog"
        private const val ARG_MODE = "mode"
        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"

        fun newInstance(
            mode: Mode,
            title: String? = null,
            message: String? = null
        ): PasscodeDialog {
            return PasscodeDialog().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_MODE, mode)
                    putString(ARG_TITLE, title)
                    putString(ARG_MESSAGE, message)
                }
            }
        }
    }

    interface PasscodeDialogListener {
        fun onPasscodeConfirmed(passcode: String)
        fun onDismiss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.MaterialDialog)
    }


    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_passcode, null)

        mode = arguments?.getSerializable(ARG_MODE) as? Mode ?: Mode.VERIFY
        setupViews(view)

        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setCancelable(true)
            .create()
            .apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
    }

    private fun setupViews(view: View) {
        passcodeInput = view.findViewById(R.id.passcodeInput)
        errorText = view.findViewById(R.id.errorText)
        messageText = view.findViewById(R.id.messageText)

        view.findViewById<TextView>(R.id.titleText).text = arguments?.getString(ARG_TITLE)
            ?: getString(when (mode) {
                Mode.VERIFY -> R.string.enter_passcode
                Mode.SET_NEW -> R.string.set_new_passcode
                Mode.CONFIRM_NEW -> R.string.confirm_passcode
            })

        messageText.text = arguments?.getString(ARG_MESSAGE)

        passcodeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                errorText.visibility = View.GONE
            }
            override fun afterTextChanged(s: Editable?) {
                if (!isProcessingInput && s?.length == 4) {
                    isProcessingInput = true
                    view.post {
                        handlePasscodeEntered(s.toString())
                        isProcessingInput = false
                    }
                }
            }
        })

        view.findViewById<View>(R.id.confirmButton).setOnClickListener {
            if (!isProcessingInput) {
                isProcessingInput = true
                handlePasscodeEntered(passcodeInput.text.toString())
                isProcessingInput = false
            }
        }

        view.findViewById<View>(R.id.cancelButton).setOnClickListener {
            dismissListener?.invoke()
            dismiss()
        }

        // Start lockout check if in verify mode
        if (mode == Mode.VERIFY) {
            checkLockoutStatus()
        }
    }

    private fun validatePasscode(passcode: String): Boolean {
        return when {
            passcode.length != 4 -> {
                showError(getString(R.string.passcode_length_error))
                false
            }
            !passcode.all { it.isDigit() } -> {
                showError(getString(R.string.passcode_digits_only))
                false
            }
            else -> true
        }
    }

    private fun checkLockoutStatus() {
        when (val lockoutState = authManager.getLockoutState()) {
            is AppAuthManager.LockoutState.Locked -> {
                updateDialogState(DialogState.Lockout(lockoutState.remainingTime))
            }
            is AppAuthManager.LockoutState.NotLocked -> {
                updateDialogState(DialogState.Input)
            }
        }
    }

    private fun updateDialogState(state: DialogState) {
        currentState = state
        when (state) {
            is DialogState.Input -> {
                enableInput()
                errorText.visibility = View.GONE
            }
            is DialogState.Lockout -> {
                disableInput()
                showLockoutState(state.remainingTime)
            }
            is DialogState.Error -> {
                enableInput()
                errorText.visibility = View.VISIBLE
            }
        }
    }

    private fun showLockoutState(remainingTime: Long) {
        updateLockoutMessage(remainingTime)

        lockoutRunnable = Runnable {
            if (remainingTime > 1000) {
                updateDialogState(DialogState.Lockout(remainingTime - 1000))
            } else {
                updateDialogState(DialogState.Input)
            }
        }
        handler.postDelayed(lockoutRunnable!!, 1000)
    }

    private fun updateLockoutMessage(remainingTime: Long) {
        val timeString = authManager.getRemainingLockoutTime()
        errorText.apply {
            text = getString(R.string.too_many_attempts, timeString)
            visibility = View.VISIBLE
        }
    }

    private fun enableInput() {
        passcodeInput.isEnabled = true
        view?.findViewById<View>(R.id.confirmButton)?.isEnabled = true
    }

    private fun disableInput() {
        passcodeInput.isEnabled = false
        view?.findViewById<View>(R.id.confirmButton)?.isEnabled = false
    }

    private fun handlePasscodeEntered(passcode: String) {
        if (!validatePasscode(passcode)) {
            clearPasscode()
            return
        }

        when (mode) {
            Mode.VERIFY -> {
                if (!authManager.canAttemptAuth()) {
                    checkLockoutStatus()
                    return
                }
                if (authManager.authenticateWithPasscode(passcode)) {
                    callback?.onPasscodeConfirmed(passcode)
                    clearPasscode()
                    dismiss()
                } else {
                    showError(getString(R.string.invalid_passcode))
                    clearPasscode()
                    checkLockoutStatus()
                }
            }
            Mode.SET_NEW -> {
                Log.d(TAG, "SET_NEW passcode entered")
                callback?.onPasscodeConfirmed(passcode)
                // Let callback handle confirmation flow
            }
            Mode.CONFIRM_NEW -> {
                Log.d(TAG, "Confirming passcode")
                callback?.onPasscodeConfirmed(passcode)
                // Let callback handle confirmation result
            }
        }
    }

    fun setCallback(callback: PasscodeDialogCallback) {
        this.callback = callback
    }

    private fun showError(message: String) {
        errorText.apply {
            text = message
            visibility = View.VISIBLE
        }
        updateDialogState(DialogState.Error)
        callback?.onError(message)
    }

    private fun clearPasscode() {
        passcodeInput.text?.clear()
        System.gc() // Help clear sensitive data from memory
    }

    fun updateDialog(title: String? = null, message: String? = null) {
        arguments?.putString(ARG_TITLE, title)
        arguments?.putString(ARG_MESSAGE, message)
        view?.findViewById<TextView>(R.id.titleText)?.text = title ?: arguments?.getString(ARG_TITLE)
        view?.findViewById<TextView>(R.id.messageText)?.text = message ?: arguments?.getString(ARG_MESSAGE)
    }

    override fun onStart() {
        super.onStart()
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                callback?.onDismiss()
                return@setOnKeyListener true
            }
            false
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        passcodeListener?.onDismiss()
        callback?.onDismiss()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (!::authManager.isInitialized) {
            dismiss()
            throw IllegalStateException("AuthManager not initialized")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        lockoutRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        clearPasscode()
        passcodeListener = null
        dismissListener = null
    }
}