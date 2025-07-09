package com.android.cts.tripleu.oldmdm

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private val TAG = "MDM_MainActivity"
    private val PREFS_FILENAME = "mdm_prefs"
    private val KEY_IS_ACTIVE = "is_active"
    private val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private val KEY_DEVICE_ID = "device_id"
    private val KEY_ACTIVATION_DATE = "activation_date" // New key for storing the date
    private val PERMISSION_REQUEST_CODE = 101

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
    private lateinit var tvDeviceId: TextView

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
            handleActivation()
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
        tvDeviceId = findViewById(R.id.tv_device_id)

        switchBrowser = findViewById(R.id.switch_browser)
        switchEmail = findViewById(R.id.switch_email)
        switchTexting = findViewById(R.id.switch_texting)
        switchRadio = findViewById(R.id.switch_radio)
        switchVideo = findViewById(R.id.switch_video)
        switchCamera = findViewById(R.id.switch_camera)
    }

    private fun handleActivation() {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Activation requires an internet connection.", Toast.LENGTH_LONG).show()
            return
        }
        ensureAuthThenProceedWithRegistration()
    }

    private fun ensureAuthThenProceedWithRegistration() {
        if (auth.currentUser != null) {
            checkPhonePermissionAndRegister()
        } else {
            auth.signInAnonymously().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    checkPhonePermissionAndRegister()
                } else {
                    Log.w(TAG, "Anonymous sign-in failed", task.exception)
                    Toast.makeText(this, "Authentication failed. Cannot register device.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkPhonePermissionAndRegister() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), PERMISSION_REQUEST_CODE)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                registerDevice()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    registerDevice()
                }
            } else {
                Toast.makeText(this, "Phone permission is required to register the device.", Toast.LENGTH_LONG).show()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @SuppressLint("HardwareIds")
    private fun registerDevice() {
        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        val imei = if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.deviceId ?: "Not Available"
        } else "Permission Denied"

        val phoneNumber = if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.line1Number ?: "Not Available"
        } else "Permission Denied"

        val deviceModel = Build.MODEL
        val activationDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val deviceId = UUID.randomUUID().toString().substring(0, 13)

        val deviceData = hashMapOf(
            "deviceId" to deviceId,
            "imei" to imei,
            "model" to deviceModel,
            "phoneNumber" to phoneNumber,
            "activationDate" to activationDate
        )

        db.collection("registered_devices").document(deviceId)
            .set(deviceData)
            .addOnSuccessListener {
                Log.d(TAG, "Device successfully registered in Firebase.")
                // Save the generated ID and the full activation date
                prefs.edit {
                    putString(KEY_DEVICE_ID, deviceId)
                        .putString(KEY_ACTIVATION_DATE, activationDate) // Save the activation date
                }

                Toast.makeText(this, "MDM Activated.", Toast.LENGTH_LONG).show()
                applyAllRestrictions(true)
                prefs.edit { putBoolean(KEY_IS_ACTIVE, true) }
                finish()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Error registering device", e)
                Toast.makeText(this, "Failed to register device. Please try again.", Toast.LENGTH_LONG).show()
            }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
        return activeNetworkInfo != null && activeNetworkInfo.isConnected
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
                val deviceId = prefs.getString(KEY_DEVICE_ID, "Not Registered")
                tvDeviceId.text = "Device ID: $deviceId"
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

    @RequiresApi(Build.VERSION_CODES.N)
    private fun checkPinForDeactivation() {
        val enteredPin = etPinEntry.text.toString()
        val activationDate = prefs.getString(KEY_ACTIVATION_DATE, null)

        if (currentOnlinePin != null && activationDate != null && activationDate.length >= 19) {
            // Get the last digit of the seconds (e.g., from "22:17:57", get "7")
            val lastSecondDigit = activationDate.substring(18, 19)
            val correctPin = "$currentOnlinePin$lastSecondDigit"

            if (enteredPin == correctPin) {
                Toast.makeText(this, "Deactivating MDM...", Toast.LENGTH_LONG).show()
                applyAllRestrictions(false)
                prefs.edit { clear() } // Clear all preferences on deactivation
                try { dpm.clearDeviceOwnerApp(packageName) } catch (e: SecurityException) { Log.e(TAG, "Failed to clear owner", e) }
                startActivity(Intent(Intent.ACTION_DELETE, "package:$packageName".toUri()))
            } else {
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Error: Could not verify PIN. Check connection and activation data.", Toast.LENGTH_LONG).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun applyAllRestrictions(enable: Boolean) {
        val restrictions = listOf(
            UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_DEBUGGING_FEATURES, UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS, UserManager.DISALLOW_SET_WALLPAPER
        )

        // Apply or clear user restrictions
        restrictions.forEach { restriction ->
            if (enable) {
                dpm.addUserRestriction(adminComponent, restriction)
            } else {
                dpm.clearUserRestriction(adminComponent, restriction)
            }
        }

        // Set or clear system update policy
        val policy = if (enable) SystemUpdatePolicy.createWindowedInstallPolicy(120, 180) else null
        dpm.setSystemUpdatePolicy(adminComponent, policy)

        // **MODIFICATION: Set or clear the maximum failed passwords policy**
        if (enable) {
            // Set the max failed attempts to 99999
            dpm.setMaximumFailedPasswordsForWipe(adminComponent, 9999999)
        } else {
            // A value of 0 disables the policy
            dpm.setMaximumFailedPasswordsForWipe(adminComponent, 0)
        }

        // Suspend or unsuspend packages based on switch states
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
                if (setAppEnabled(pkg, false)) { // false to disable/suspend
                    successfullyBlocked.add(pkg)
                }
            }
            prefs.edit { putStringSet(KEY_BLOCKED_PACKAGES, successfullyBlocked) }
        } else {
            val blockedPackages = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())
            blockedPackages?.forEach { pkg -> setAppEnabled(pkg, true) } // true to enable/unsuspend
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