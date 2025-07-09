package com.android.cts.tripleu.oldmdm

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

// --- FIX: Added the missing import for the R class ---

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private val TAG = "MDM_MainActivity"
    private val PREFS_FILENAME = "mdm_prefs"
    private val KEY_IS_ACTIVE = "is_active"
    private val KEY_BLOCKED_PACKAGES = "blocked_packages"

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var prefs: SharedPreferences

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var currentOnlinePin: String? = null

    // UI Views
    private lateinit var setupView: View
    private lateinit var activateView: View
    private lateinit var deactivateView: View
    private lateinit var setupInstructions: TextView
    private lateinit var btnActivate: Button
    private lateinit var btnDeactivate: Button
    private lateinit var etPinEntry: EditText
    private lateinit var tvPinStatus: TextView

    // Switches
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var switchBrowser: Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var switchEmail: Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var switchTexting: Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var switchRadio: Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var switchVideo: Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var switchCamera: Switch

    // Package Lists
    private val browserPackages = listOf("com.android.browser", "com.android.chrome", "com.android.quicksearchbox", "com.android.vending", "org.chromium.chrome")
    private val emailPackages = listOf("com.lge.email", "com.android.email")
    private val textingPackages = listOf("com.android.mms", "com.verizon.messaging.vzmsgs")
    private val radioPackages = listOf("com.lge.fmradio", "jp.kyocera.kc_fmradio", "com.caf.fmradio", "com.umx.fmradio")
    private val videoPackages = listOf("com.lge.videoplayer")
    private val cameraPackages = listOf("com.lge.camera", "com.android.camera", "com.sonim.camera", "com.tcl.camera", "com.android.camera2")

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        prefs = getSharedPreferences(PREFS_FILENAME, MODE_PRIVATE)

        auth = Firebase.auth
        db = Firebase.firestore

        initializeViews()
        setupSwitchListeners()
        updateUiState()

        btnActivate.setOnClickListener {
            Toast.makeText(this, "MDM Activated.", Toast.LENGTH_LONG).show()
            applyAllRestrictions(true)
            prefs.edit { putBoolean(KEY_IS_ACTIVE, true) }
            finish()
        }

        btnDeactivate.setOnClickListener {
            checkPinForDeactivation()
        }
    }

    private fun initializeViews() {
        setupView = findViewById(R.id.setup_view)
        activateView = findViewById(R.id.activate_view)
        deactivateView = findViewById(R.id.deactivate_view)
        setupInstructions = findViewById(R.id.setup_instructions)
        btnActivate = findViewById(R.id.btn_activate_filter)
        btnDeactivate = findViewById(R.id.btn_deactivate)
        etPinEntry = findViewById(R.id.et_pin_entry)
        tvPinStatus = findViewById(R.id.pin_status_view)

        // Initialize switches
        switchBrowser = findViewById(R.id.switch_browser)
        switchEmail = findViewById(R.id.switch_email)
        switchTexting = findViewById(R.id.switch_texting)
        switchRadio = findViewById(R.id.switch_radio)
        switchVideo = findViewById(R.id.switch_video)
        switchCamera = findViewById(R.id.switch_camera)
    }

    private fun setupSwitchListeners() {
        val editor = prefs.edit()
        switchBrowser.setOnCheckedChangeListener { _, isChecked -> editor.putBoolean("switch_browser", isChecked).apply() }
        switchEmail.setOnCheckedChangeListener { _, isChecked -> editor.putBoolean("switch_email", isChecked).apply() }
        switchTexting.setOnCheckedChangeListener { _, isChecked -> editor.putBoolean("switch_texting", isChecked).apply() }
        switchRadio.setOnCheckedChangeListener { _, isChecked -> editor.putBoolean("switch_radio", isChecked).apply() }
        switchVideo.setOnCheckedChangeListener { _, isChecked -> editor.putBoolean("switch_video", isChecked).apply() }
        switchCamera.setOnCheckedChangeListener { _, isChecked -> editor.putBoolean("switch_camera", isChecked).apply() }
    }

    private fun loadSwitchStates() {
        switchBrowser.isChecked = prefs.getBoolean("switch_browser", false)
        switchEmail.isChecked = prefs.getBoolean("switch_email", false)
        switchTexting.isChecked = prefs.getBoolean("switch_texting", false)
        switchRadio.isChecked = prefs.getBoolean("switch_radio", false)
        switchVideo.isChecked = prefs.getBoolean("switch_video", false)
        switchCamera.isChecked = prefs.getBoolean("switch_camera", false)
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiState() {
        if (!dpm.isDeviceOwnerApp(packageName)) {
            setupView.visibility = View.VISIBLE
            activateView.visibility = View.GONE
            deactivateView.visibility = View.GONE
            val adbCommand = "adb shell dpm set-device-owner $packageName/.MyDeviceAdminReceiver"
            setupInstructions.text = "To use this app, grant Device Owner permission via ADB on a factory-reset device by running:\n\n$adbCommand"
        } else {
            val isActive = prefs.getBoolean(KEY_IS_ACTIVE, false)
            if (isActive) {
                setupView.visibility = View.GONE
                activateView.visibility = View.GONE
                deactivateView.visibility = View.VISIBLE
                signInAndListenForPin()
            } else {
                setupView.visibility = View.GONE
                activateView.visibility = View.VISIBLE
                deactivateView.visibility = View.GONE
                loadSwitchStates()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun signInAndListenForPin() {
        if (auth.currentUser != null) listenForPinUpdates()
        else auth.signInAnonymously().addOnCompleteListener {
            if (it.isSuccessful) listenForPinUpdates()
            else tvPinStatus.text = "Auth Failed"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun listenForPinUpdates() {
        db.collection("device_pins").document("current_pin")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    tvPinStatus.text = "Status: Offline"; return@addSnapshotListener
                }
                currentOnlinePin = snapshot?.getString("pin")
                tvPinStatus.text = if (currentOnlinePin != null) "Status: Online" else "Status: Awaiting PIN..."
            }
    }

    private fun checkPinForDeactivation() {
        if (etPinEntry.text.toString() == currentOnlinePin) {
            Toast.makeText(this, "Deactivating MDM...", Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                applyAllRestrictions(false)
            }
            prefs.edit {putBoolean(KEY_IS_ACTIVE, false)}
            try { dpm.clearDeviceOwnerApp(packageName) } catch (e: SecurityException) { Log.e(TAG, "Failed to clear owner", e) }
            startActivity(Intent(Intent.ACTION_DELETE, "package:$packageName".toUri()))
        } else {
            Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun applyAllRestrictions(enable: Boolean) {
        // Apply general restrictions
        val restrictions = listOf(
            UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_DEBUGGING_FEATURES, UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS, UserManager.DISALLOW_SET_WALLPAPER
        )
        restrictions.forEach { restriction ->
            if (enable) dpm.addUserRestriction(adminComponent, restriction)
            else dpm.clearUserRestriction(adminComponent, restriction)
        }

        // System Update Policy
        val policy = if (enable) SystemUpdatePolicy.createWindowedInstallPolicy(120, 180) else null
        dpm.setSystemUpdatePolicy(adminComponent, policy)

        // Apply app-specific restrictions based on switches
        val packagesToBlock = mutableSetOf<String>()
        if (enable) {
            if (switchBrowser.isChecked) packagesToBlock.addAll(browserPackages)
            if (switchEmail.isChecked) packagesToBlock.addAll(emailPackages)
            if (switchTexting.isChecked) packagesToBlock.addAll(textingPackages)
            if (switchRadio.isChecked) packagesToBlock.addAll(radioPackages)
            if (switchVideo.isChecked) packagesToBlock.addAll(videoPackages)
            if (switchCamera.isChecked) packagesToBlock.addAll(cameraPackages)

            val successfullyBlocked = mutableSetOf<String>()
            packagesToBlock.forEach { pkg ->
                if (setAppEnabled(pkg, false)) {
                    successfullyBlocked.add(pkg)
                }
            }
            prefs.edit { putStringSet(KEY_BLOCKED_PACKAGES, successfullyBlocked) }
        } else {
            // Re-enable only the apps that were successfully blocked
            val blockedPackages = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())
            blockedPackages?.forEach { pkg -> setAppEnabled(pkg, true) }
            prefs.edit { remove(KEY_BLOCKED_PACKAGES) }
        }
    }

    private fun setAppEnabled(packageName: String, enabled: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), !enabled)
            } else {
                dpm.setApplicationHidden(adminComponent, packageName, !enabled)
            }
            Log.d(TAG, "Set ${if (enabled) "enabled" else "disabled"}: $packageName")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set state for $packageName", e)
            false
        }
    }
}
