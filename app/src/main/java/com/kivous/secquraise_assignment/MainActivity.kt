package com.kivous.secquraise_assignment

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.icu.text.SimpleDateFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.widget.addTextChangedListener
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.kivous.secquraise_assignment.databinding.ActivityMainBinding
import com.kivous.secquraise_assignment.models.DataModel
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.PictureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date

@SuppressLint("SimpleDateFormat", "SetTextI18n")
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val db = Firebase.firestore
    val storageRef = Firebase.storage.reference
    var captureCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.cameraView.setLifecycleOwner(this)

        locationPermission()
        setFrequency()

        binding.cameraView.addCameraListener(object : CameraListener() {
            override fun onPictureTaken(result: PictureResult) {
                result.toBitmap(340, 460) { bitmap ->
                    val imgData = result.data
                    binding.ivImage.setImageBitmap(bitmap)
                    captureCount++
                    binding.tvCaptureCount.text = captureCount.toString()

                    val filename = SimpleDateFormat("dd-MM-yyy_HH_mm_ss").format(Date())
                    val imgRef = storageRef.child("images/$filename")
                    CoroutineScope(Dispatchers.IO).launch {
                        // upload image to Firebase Storage
                        imgRef.putBytes(imgData).await()
                        // get image url
                        val url = imgRef.downloadUrl.await()
                        getLocation { lat, long ->
                            val data = DataModel(
                                getCurrentDateTime(),
                                binding.tvCaptureCount.text.toString(),
                                binding.etFrequency.text.toString(),
                                checkConnectivity(),
                                checkBatteryCharging(),
                                getBatteryPercentage(),
                                "$lat, $long",
                                url.toString()
                            )
                            CoroutineScope(Dispatchers.IO).launch {
                                // add data to Firebase FireStore
                                db.collection("data").add(data).await()
                                withContext(Dispatchers.Main) {
                                }
                            }
                        }
                    }
                }
            }
        })

        binding.btnRefresh.setOnClickListener {
            setDataToViews()
        }

        getFrequency { data ->
            val millis = data.toLong() * 60 * 1000
            val handler = Handler()
            var refresh = Runnable {}
            refresh = Runnable {
                setDataToViews()
                handler.postDelayed(refresh, millis)
            }
            handler.post(refresh)
        }


    }

    override fun onResume() {
        super.onResume()
        binding.cameraView.open()
        Handler(Looper.getMainLooper()).postDelayed(
            {
                setDataToViews()
            },
            500
        )
    }


    override fun onPause() {
        super.onPause()
        binding.cameraView.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.cameraView.destroy()
    }

    private fun setDataToViews() {
        binding.apply {
            tvDateAndTime.text = getCurrentDateTime()
            tvBatteryPercentage.text = "${getBatteryPercentage()}%"
            tvConnectivity.text = checkConnectivity()
            tvBatteryCharging.text = checkBatteryCharging()
            getFrequency { data ->
                etFrequency.setText(data)
            }
        }
        getLocation { lat, long ->
            binding.tvLocation.text = "$lat, $long"
        }
        binding.cameraView.takePicture()
    }


    private fun getCurrentDateTime(): String {
        return SimpleDateFormat("dd-MM-yyy HH:mm:ss").format(Date())
    }

    private fun checkBatteryCharging(): String {
        val batteryStatus: Intent? =
            IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { intentFilter ->
                this.registerReceiver(null, intentFilter)
            }
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL
        return if (isCharging) {
            "ON"
        } else {
            "OFF"
        }
    }

    private fun getBatteryPercentage(): String {
        val batteryManager = this.getSystemService(BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString()
    }

    private fun checkConnectivity(): String {
        if (hasInternetConnection()) {
            return "ON"
        }
        return "OFF"
    }

    private fun hasInternetConnection(): Boolean {
        val connectivityManager = application.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return when {
            capabilities.hasTransport(TRANSPORT_WIFI) -> true
            capabilities.hasTransport(TRANSPORT_CELLULAR) -> true
            capabilities.hasTransport(TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    private fun locationPermission() {
        val locationPermissionRequest = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            when {
                permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {

                }

                permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {

                }

                else -> {

                }
            }
        }

        locationPermissionRequest.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun getLocation(loc: (Double, Double) -> Unit) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val location = fusedLocationClient.lastLocation.await()
            withContext(Dispatchers.Main) {
                loc.invoke(location.latitude, location.longitude)
            }
        }
    }


    private fun setFrequency() {
        var job: Job? = null
        binding.etFrequency.addTextChangedListener { editable ->
            job?.cancel()
            job = MainScope().launch {
                delay(1000L)
                editable?.let {
                    if (editable.toString().isNotEmpty()) {
                        setFrequencyToDatabase(editable.toString())
                    }
                }
            }
        }
    }

    private fun setFrequencyToDatabase(data: String) {
        CoroutineScope(Dispatchers.IO).launch {
            db.collection("more_info").document("frequency")
                .set(hashMapOf("frequency" to data))
                .await()
            withContext(Dispatchers.Main) {
                binding.etFrequency.clearFocus()
            }
        }
    }

    private fun getFrequency(data: (String) -> Unit) {
        db.collection("more_info").document("frequency").addSnapshotListener { snapshot, _ ->

            if (snapshot != null && snapshot.exists()) {
                data.invoke(snapshot.data?.get("frequency").toString())
            } else {
                Log.d("ERR", "Current data: null")
            }
        }
    }

}

