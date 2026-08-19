package com.rahgozar.app.ui.base

import android.os.Bundle
import com.rahgozar.app.enums.PermissionType
import com.rahgozar.app.helper.PermissionHelper

/**
 * Adds runtime permission handling to [BaseComponentActivity].
 *
 * It used to offer a file chooser and a QR scanner too. Both were routes for a
 * configuration to enter the app from outside the panel, so both are gone along
 * with the CAMERA permission they required.
 */
abstract class HelperBaseComponentActivity : BaseComponentActivity() {

    private lateinit var permissionRequester: PermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionRequester = PermissionHelper(this)
    }

    /**
     * Check if permission is granted and request it if not.
     *
     * @param permissionType The type of permission to check and request
     * @param onGranted Callback to execute when permission is granted
     */
    protected fun checkAndRequestPermission(
        permissionType: PermissionType,
        onGranted: () -> Unit
    ) {
        permissionRequester.request(permissionType, onGranted)
    }
}
