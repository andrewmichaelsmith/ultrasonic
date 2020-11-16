package org.moire.ultrasonic.activity

import android.app.AlertDialog
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import java.net.MalformedURLException
import java.net.URL
import org.koin.android.ext.android.inject
import org.koin.android.viewmodel.ext.android.viewModel
import org.moire.ultrasonic.BuildConfig
import org.moire.ultrasonic.R
import org.moire.ultrasonic.api.subsonic.SubsonicAPIClient
import org.moire.ultrasonic.api.subsonic.SubsonicAPIVersions
import org.moire.ultrasonic.api.subsonic.SubsonicClientConfiguration
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.data.ServerSetting
import org.moire.ultrasonic.service.ApiCallResponseChecker.Companion.checkResponseSuccessful
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.ErrorDialog
import org.moire.ultrasonic.util.ModalBackgroundTask
import org.moire.ultrasonic.util.Util
import timber.log.Timber

/**
 * This Activity provides a Form which can be used to edit the properties of a Server Setting.
 * It can also be used to create a Server Setting from scratch.
 * Contains functions for testing the configured Server Setting
 */
internal class EditServerActivity : AppCompatActivity() {

    companion object {
        const val EDIT_SERVER_INTENT_INDEX = "index"
    }

    private val serverSettingsModel: ServerSettingsModel by viewModel()
    private val activeServerProvider: ActiveServerProvider by inject()

    private var currentServerSetting: ServerSetting? = null

    private var serverNameEditText: TextInputLayout? = null
    private var serverAddressEditText: TextInputLayout? = null
    private var userNameEditText: TextInputLayout? = null
    private var passwordEditText: TextInputLayout? = null
    private var selfSignedSwitch: SwitchMaterial? = null
    private var ldapSwitch: SwitchMaterial? = null
    private var jukeboxSwitch: SwitchMaterial? = null
    private var saveButton: Button? = null
    private var testButton: Button? = null
    private var isInstanceStateSaved: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Util.applyTheme(this)
        if (savedInstanceState == null) configureActionBar()

        setContentView(R.layout.server_edit)

        serverNameEditText = findViewById(R.id.edit_server_name)
        serverAddressEditText = findViewById(R.id.edit_server_address)
        userNameEditText = findViewById(R.id.edit_server_username)
        passwordEditText = findViewById(R.id.edit_server_password)
        selfSignedSwitch = findViewById(R.id.edit_self_signed)
        ldapSwitch = findViewById(R.id.edit_ldap)
        jukeboxSwitch = findViewById(R.id.edit_jukebox)
        saveButton = findViewById(R.id.edit_save)
        testButton = findViewById(R.id.edit_test)

        val index = intent.getIntExtra(EDIT_SERVER_INTENT_INDEX, -1)

        if (index != -1) {
            // Editing an existing server
            setTitle(R.string.server_editor_label)
            val serverSetting = serverSettingsModel.getServerSetting(index)
            serverSetting.observe(
                this,
                Observer { t ->
                    if (t != null) {
                        currentServerSetting = t
                        if (!isInstanceStateSaved) setFields()
                        // Remove the minimum API version so it can be detected again
                        if (currentServerSetting?.minimumApiVersion != null) {
                            currentServerSetting!!.minimumApiVersion = null
                            serverSettingsModel.updateItem(currentServerSetting)
                            if (
                                activeServerProvider.getActiveServer().id ==
                                currentServerSetting!!.id
                            ) {
                                MusicServiceFactory.resetMusicService()
                            }
                        }
                    }
                }
            )
            saveButton!!.setOnClickListener {
                if (currentServerSetting != null) {
                    if (getFields()) {
                        serverSettingsModel.updateItem(currentServerSetting)
                        // Apply modifications if the current server was modified
                        if (
                            activeServerProvider.getActiveServer().id ==
                            currentServerSetting!!.id
                        ) {
                            MusicServiceFactory.resetMusicService()
                        }
                        finish()
                    }
                }
            }
        } else {
            // Creating a new server
            setTitle(R.string.server_editor_new_label)
            currentServerSetting = ServerSetting()
            saveButton!!.setOnClickListener {
                if (getFields()) {
                    serverSettingsModel.saveNewItem(currentServerSetting)
                    finish()
                }
            }
        }

        testButton!!.setOnClickListener {
            if (getFields()) {
                testConnection()
            }
        }
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.putString(
            ::serverNameEditText.name, serverNameEditText!!.editText?.text.toString()
        )
        savedInstanceState.putString(
            ::serverAddressEditText.name, serverAddressEditText!!.editText?.text.toString()
        )
        savedInstanceState.putString(
            ::userNameEditText.name, userNameEditText!!.editText?.text.toString()
        )
        savedInstanceState.putString(
            ::passwordEditText.name, passwordEditText!!.editText?.text.toString()
        )
        savedInstanceState.putBoolean(
            ::selfSignedSwitch.name, selfSignedSwitch!!.isChecked
        )
        savedInstanceState.putBoolean(
            ::ldapSwitch.name, ldapSwitch!!.isChecked
        )
        savedInstanceState.putBoolean(
            ::jukeboxSwitch.name, jukeboxSwitch!!.isChecked
        )
        savedInstanceState.putBoolean(
            ::isInstanceStateSaved.name, true
        )

        super.onSaveInstanceState(savedInstanceState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        serverNameEditText!!.editText?.setText(
            savedInstanceState.getString(::serverNameEditText.name)
        )
        serverAddressEditText!!.editText?.setText(
            savedInstanceState.getString(::serverAddressEditText.name)
        )
        userNameEditText!!.editText?.setText(
            savedInstanceState.getString(::userNameEditText.name)
        )
        passwordEditText!!.editText?.setText(
            savedInstanceState.getString(::passwordEditText.name)
        )
        selfSignedSwitch!!.isChecked = savedInstanceState.getBoolean(::selfSignedSwitch.name)
        ldapSwitch!!.isChecked = savedInstanceState.getBoolean(::ldapSwitch.name)
        jukeboxSwitch!!.isChecked = savedInstanceState.getBoolean(::jukeboxSwitch.name)
        isInstanceStateSaved = savedInstanceState.getBoolean(::isInstanceStateSaved.name)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishActivity()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        finishActivity()
    }

    private fun configureActionBar() {
        val actionBar: ActionBar? = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayShowHomeEnabled(true)
            actionBar.setDisplayHomeAsUpEnabled(true)
        }
    }

    /**
     * Sets the values of the Form from the current Server Setting instance
     */
    private fun setFields() {
        if (currentServerSetting == null) return

        serverNameEditText!!.editText?.setText(currentServerSetting!!.name)
        serverAddressEditText!!.editText?.setText(currentServerSetting!!.url)
        userNameEditText!!.editText?.setText(currentServerSetting!!.userName)
        passwordEditText!!.editText?.setText(currentServerSetting!!.password)
        selfSignedSwitch!!.isChecked = currentServerSetting!!.allowSelfSignedCertificate
        ldapSwitch!!.isChecked = currentServerSetting!!.ldapSupport
        jukeboxSwitch!!.isChecked = currentServerSetting!!.jukeboxByDefault
    }

    /**
     * Retrieves the values in the Form to the current Server Setting instance
     * This function also does some basic validation on the fields
     */
    private fun getFields(): Boolean {
        if (currentServerSetting == null) return false
        var isValid = true
        var url: URL? = null

        if (serverAddressEditText!!.editText?.text.isNullOrBlank()) {
            serverAddressEditText!!.error = getString(R.string.server_editor_required)
            isValid = false
        } else {
            try {
                val urlString = serverAddressEditText!!.editText?.text.toString()
                url = URL(urlString)
                if (
                    urlString != urlString.trim(' ') ||
                    url.host.isNullOrBlank()
                ) {
                    throw MalformedURLException()
                }
                serverAddressEditText!!.error = null
            } catch (exception: MalformedURLException) {
                serverAddressEditText!!.error = getString(R.string.settings_invalid_url)
                isValid = false
            }
        }

        if (serverNameEditText!!.editText?.text.isNullOrBlank()) {
            if (isValid && url != null) {
                serverNameEditText!!.editText?.setText(url.host)
            }
        }

        if (userNameEditText!!.editText?.text.isNullOrBlank()) {
            userNameEditText!!.error = getString(R.string.server_editor_required)
            isValid = false
        } else {
            userNameEditText!!.error = null
        }

        if (isValid) {
            currentServerSetting!!.name = serverNameEditText!!.editText?.text.toString()
            currentServerSetting!!.url = serverAddressEditText!!.editText?.text.toString()
            currentServerSetting!!.userName = userNameEditText!!.editText?.text.toString()
            currentServerSetting!!.password = passwordEditText!!.editText?.text.toString()
            currentServerSetting!!.allowSelfSignedCertificate = selfSignedSwitch!!.isChecked
            currentServerSetting!!.ldapSupport = ldapSwitch!!.isChecked
            currentServerSetting!!.jukeboxByDefault = jukeboxSwitch!!.isChecked
        }

        return isValid
    }

    /**
     * Checks whether any value in the fields are changed according to their original values.
     */
    private fun areFieldsChanged(): Boolean {
        if (currentServerSetting == null || currentServerSetting!!.id == -1) {
            return !serverNameEditText!!.editText?.text!!.isBlank() ||
                serverAddressEditText!!.editText?.text.toString() != "http://" ||
                !userNameEditText!!.editText?.text!!.isBlank() ||
                !passwordEditText!!.editText?.text!!.isBlank()
        }

        return currentServerSetting!!.name != serverNameEditText!!.editText?.text.toString() ||
            currentServerSetting!!.url != serverAddressEditText!!.editText?.text.toString() ||
            currentServerSetting!!.userName != userNameEditText!!.editText?.text.toString() ||
            currentServerSetting!!.password != passwordEditText!!.editText?.text.toString() ||
            currentServerSetting!!.allowSelfSignedCertificate != selfSignedSwitch!!.isChecked ||
            currentServerSetting!!.ldapSupport != ldapSwitch!!.isChecked ||
            currentServerSetting!!.jukeboxByDefault != jukeboxSwitch!!.isChecked
    }

    /**
     * Tests if the network connection to the entered Server Settings can be made
     */
    private fun testConnection() {
        val task: ModalBackgroundTask<Boolean> = object : ModalBackgroundTask<Boolean>(
            this,
            false
        ) {

            @Throws(Throwable::class)
            override fun doInBackground(): Boolean {
                updateProgress(R.string.settings_testing_connection)
                val configuration = SubsonicClientConfiguration(
                    currentServerSetting!!.url,
                    currentServerSetting!!.userName,
                    currentServerSetting!!.password,
                    SubsonicAPIVersions.getClosestKnownClientApiVersion(
                        Constants.REST_PROTOCOL_VERSION
                    ),
                    Constants.REST_CLIENT_ID,
                    currentServerSetting!!.allowSelfSignedCertificate,
                    currentServerSetting!!.ldapSupport,
                    BuildConfig.DEBUG
                )
                val subsonicApiClient = SubsonicAPIClient(configuration)

                // Execute a ping to retrieve the API version.
                // This is accepted to fail if the authentication is incorrect yet.
                var pingResponse = subsonicApiClient.api.ping().execute()
                if (pingResponse?.body() != null) {
                    val restApiVersion = pingResponse.body()!!.version.restApiVersion
                    currentServerSetting!!.minimumApiVersion = restApiVersion
                    Timber.i("Server minimum API version set to %s", restApiVersion)
                }

                // Execute a ping to check the authentication, now using the correct API version.
                pingResponse = subsonicApiClient.api.ping().execute()
                checkResponseSuccessful(pingResponse)

                val licenseResponse = subsonicApiClient.api.getLicense().execute()
                checkResponseSuccessful(licenseResponse)
                return licenseResponse.body()!!.license.valid
            }

            override fun done(licenseValid: Boolean) {
                if (licenseValid) {
                    Util.toast(activity, R.string.settings_testing_ok)
                } else {
                    Util.toast(activity, R.string.settings_testing_unlicensed)
                }
            }

            override fun error(error: Throwable) {
                Timber.w(error)
                ErrorDialog(
                    activity,
                    String.format(
                        "%s %s",
                        resources.getString(R.string.settings_connection_failure),
                        getErrorMessage(error)
                    ),
                    false
                )
            }
        }
        task.execute()
    }

    /**
     * Finishes the Activity, after confirmation from the user if needed
     */
    private fun finishActivity() {
        if (areFieldsChanged()) {
            AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(R.string.common_confirm)
                .setMessage(R.string.server_editor_leave_confirmation)
                .setPositiveButton(R.string.common_ok) { dialog, _ ->
                    dialog.dismiss()
                    finish()
                }
                .setNegativeButton(R.string.common_cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        } else {
            finish()
        }
    }
}
