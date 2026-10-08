package com.androidcast.controller

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView

/** Lists the phone's paired Bluetooth devices so you can pick the Fire TV stick. */
class DevicePickerActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var devices: List<BluetoothDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)
        list = findViewById(R.id.devices)
        empty = findViewById(R.id.empty)

        findViewById<Button>(R.id.pairNew).setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val device = devices[position]
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_DEVICE, device.address).apply()
            startActivity(
                Intent(this, RemoteActivity::class.java).putExtra(RemoteActivity.EXTRA_ADDRESS, device.address)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh every time we come back, e.g. after pairing in system settings.
        if (hasBluetoothPermission()) loadDevices() else requestBluetoothPermission()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasBluetoothPermission()) {
            loadDevices()
        } else {
            showEmpty("Bluetooth permission is needed to see your paired devices.\n\nAllow \"Nearby devices\" for this app in Settings.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        when {
            adapter == null -> return showEmpty("This phone has no Bluetooth.")
            !adapter.isEnabled -> return showEmpty("Bluetooth is off. Turn it on, then come back.")
        }
        val last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_DEVICE, null)
        devices = adapter!!.bondedDevices.orEmpty().sortedWith(
            compareByDescending<BluetoothDevice> { it.address == last }
                .thenByDescending { looksLikeTv(it) }
                .thenBy { (it.name ?: "").lowercase() }
        )
        if (devices.isEmpty()) {
            return showEmpty("No paired devices yet.\n\nTap \"Pair a new device\" and pair with your Fire TV stick " +
                "(on the stick: open AndroidCast, press Menu, then Up to make it visible).")
        }
        empty.visibility = View.GONE
        list.visibility = View.VISIBLE
        list.adapter = object : ArrayAdapter<BluetoothDevice>(this, android.R.layout.simple_list_item_2, android.R.id.text1, devices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val d = devices[position]
                val tags = listOfNotNull(
                    "last used".takeIf { d.address == last },
                    "TV / media device".takeIf { looksLikeTv(d) },
                )
                view.findViewById<TextView>(android.R.id.text1).text = d.name ?: d.address
                view.findViewById<TextView>(android.R.id.text2).text =
                    (listOf(d.address) + tags).joinToString("  ·  ")
                return view
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun looksLikeTv(d: BluetoothDevice): Boolean {
        val name = (d.name ?: "").lowercase()
        return d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            listOf("fire", "aft", "tv", "stick").any { it in name }
    }

    private fun showEmpty(text: String) {
        list.visibility = View.GONE
        empty.visibility = View.VISIBLE
        empty.text = text
    }

    private fun hasBluetoothPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    companion object {
        const val PREFS = "controller"
        const val LAST_DEVICE = "last_device"
    }
}
