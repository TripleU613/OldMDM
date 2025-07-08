package com.tripleu.oldmdm

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private val TAG = "MDM_MainActivity"

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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        prefs = getSharedPreferences("mdm_prefs", MODE_PRIVATE)

        // Initialize Firebase
        auth = Firebase.auth
        db = Firebase.firestore

        initializeViews()
        updateUiState()

        btnActivate.setOnClickListener {
            Toast.makeText(this, "MDM Activated.", Toast.LENGTH_LONG).show()

            applyAllRestrictions(true)

            prefs.edit {putBoolean("is_active", true)}

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
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiState() {
        if (!dpm.isDeviceOwnerApp(packageName)) {
            // State 1: Not Device Owner
            setupView.visibility = View.VISIBLE
            activateView.visibility = View.GONE
            deactivateView.visibility = View.GONE
            val adbCommand = "adb shell dpm set-device-owner ${packageName}/.MyDeviceAdminReceiver"
            setupInstructions.text = "To use this app, grant Device Owner permission via ADB on a factory-reset device by running:\n\n$adbCommand"
        } else {
            val isActive = prefs.getBoolean("is_active", false)
            if (isActive) {
                // State 3: Device Owner and Active
                setupView.visibility = View.GONE
                activateView.visibility = View.GONE
                deactivateView.visibility = View.VISIBLE
                signInAndListenForPin()
            } else {
                // State 2: Device Owner but Inactive
                setupView.visibility = View.GONE
                activateView.visibility = View.VISIBLE
                deactivateView.visibility = View.GONE
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun signInAndListenForPin() {
        if (auth.currentUser != null) {
            listenForPinUpdates()
        } else {
            auth.signInAnonymously().addOnCompleteListener {
                if (it.isSuccessful) {
                    listenForPinUpdates()
                } else {
                    tvPinStatus.text = "Auth Failed"
                    Toast.makeText(this, "Could not connect to auth service.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun listenForPinUpdates() {
        db.collection("device_pins").document("current_pin")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e)
                    tvPinStatus.text = "Status: Offline"
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    currentOnlinePin = snapshot.getString("pin")
                    tvPinStatus.text = "Status: Online"
                } else {
                    tvPinStatus.text = "Status: Awaiting PIN..."
                }
            }
    }

    private fun checkPinForDeactivation() {
        val enteredPin = etPinEntry.text.toString()
        if (enteredPin == currentOnlinePin) {
            Toast.makeText(this, "Deactivating MDM...", Toast.LENGTH_LONG).show()
            applyAllRestrictions(false) // Revert all restrictions
            prefs.edit { putBoolean("is_active", false)}

            try {
                dpm.clearDeviceOwnerApp(packageName)
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to clear device owner.", e)
            }

            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)

        } else {
            Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("InlinedApi")
    private fun applyAllRestrictions(enable: Boolean) {
        // Find the default browser
        val browserIntent = Intent(Intent.ACTION_VIEW, "http://".toUri())
        @Suppress("DEPRECATION")
        val resolveInfo = packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val browserPackageName = resolveInfo?.activityInfo?.packageName

        if (enable) {
            // --- APPLY RESTRICTIONS ---
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SET_WALLPAPER)

            dpm.setSystemUpdatePolicy(adminComponent, SystemUpdatePolicy.createWindowedInstallPolicy(120, 180))


            if (browserPackageName != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        dpm.setPackagesSuspended(adminComponent, arrayOf(browserPackageName), true)
                    } else {
                        dpm.setApplicationHidden(adminComponent, browserPackageName, true)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disable browser", e)
                }
            }

        } else {
            // --- REMOVE RESTRICTIONS ---
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SET_WALLPAPER)

            dpm.setSystemUpdatePolicy(adminComponent, null)

            if (browserPackageName != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        dpm.setPackagesSuspended(adminComponent, arrayOf(browserPackageName), false)
                    } else {
                        dpm.setApplicationHidden(adminComponent, browserPackageName, false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to re-enable browser", e)
                }
            }
        }
    }
}