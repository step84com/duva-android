package com.step84.duva

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.*
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.GeoPoint
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.fragment_home.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(),
    HomeFragment.OnFragmentInteractionListener,
    ZonesFragment.OnFragmentInteractionListener,
    SettingsFragment.OnFragmentInteractionListener {

    private val TAG = "MainActivity"
    private val CHANNELID = "0";
    private val UNIQUEWORKSTRING = "duvauniquework"
    private val requestCodeAccessFineLocation = 101
    private val requestCodeWriteExternalStorage = 102
    private val requestCodeRecordAudio = 103

    var currentUser: User? = null
    var currentSubscriptions: MutableList<Subscription>? = null
    var currentLocation: GeoPoint? = null
    var allZones: MutableList<Zone>? = null

    private lateinit var auth: FirebaseAuth

    private var googleMapInterface: GoogleMapInterface? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest
    private lateinit var geofencingClient: GeofencingClient
    private var geofenceList: MutableList<Geofence> = mutableListOf()

    /*
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceTransitionsIntentService::class.java)
        Log.i(TAG, "duva: geofence starting intent service")
        //PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
    }
     */

    private val geofencePendingIntent: PendingIntent by lazy {
        Log.i(TAG, "duva: geofence intent after getBroadcast()")
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private val mOnNavigationItemSelectedListener = BottomNavigationView.OnNavigationItemSelectedListener { item ->
        when (item.itemId) {
            R.id.navigation_home -> {
                switchFragment(HomeFragment(), "HomeFragment")
                return@OnNavigationItemSelectedListener true
            }
            R.id.navigation_zones -> {
                switchFragment(ZonesFragment(), "ZonesFragment")
                return@OnNavigationItemSelectedListener true
            }
            R.id.navigation_settings -> {
                switchFragment(SettingsFragment(), "SettingsFragment")
                return@OnNavigationItemSelectedListener true
            }
        }
        false
    }

    private val br = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "duva: geofence received intent in onReceive() in MainActivity")
            when(intent?.action) {
                "com.step84.duva.GEOFENCE_ENTER" -> geofenceTransition("enter", intent.extras!!.getString("zoneid", "0"))
                "com.step84.duva.GEOFENCE_DWELL" -> geofenceTransition("dwell", intent.extras!!.getString("zoneid", "0"))
                "com.step84.duva.GEOFENCE_EXIT" -> geofenceTransition("exit", intent.extras!!.getString("zoneid", "0"))
            }
        }
    }

    private val filter = IntentFilter("com.step84.duva.GEOFENCE_ENTER").apply {
        addAction("com.step84.duva.GEOFENCE_EXIT")
        addAction("com.step84.duva.GEOFENCE_DWELL")
    }

    private fun switchFragment(f: Fragment, t: String) {
        supportFragmentManager.beginTransaction().replace(R.id.container, f, t).commit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.i(TAG, "duva: startup before setup permissions")
        /*
        setupPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        setupPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        setupPermission(Manifest.permission.RECORD_AUDIO)
        */
        val permissionsNeeded = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO)
        ActivityCompat.requestPermissions(this, permissionsNeeded, 200)

        auth = FirebaseAuth.getInstance()
        setMapListener(ZonesFragment())
        checkPermissionsAndInitialize()
        createNotificationChannel()

        if(checkPermission(Manifest.permission.FOREGROUND_SERVICE)) {
            Log.i(TAG, "duva: foreground service permitted, starting..")
            ForegroundService.startService(this, "Service is running..")
        } else if(Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.i(TAG, "duva: foreground service less than P, starting..")
            ForegroundService.startService(this, "Service is running..")
        } else {
            Log.d(TAG, "duva: foreground service not permitted")
        }

        Log.i(TAG, "duva: currentUser object in MainActivity = " + currentUser?.lastLocation) // Should return null
    }

    fun checkPermissionsAndInitialize() {
        if(checkPermission(Manifest.permission.ACCESS_FINE_LOCATION) && checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) && checkPermission(Manifest.permission.RECORD_AUDIO)) {
            Log.i(TAG, "duva: startup all permissions ok, resuming")
            Globals.permissionsGranted = true
            setupUser()
            setupSubscriptions()
            setupZones()
        }


    }

    override fun onFragmentInteraction(uri: Uri) {
    }

    override fun onStart() {
        super.onStart()
        startLocationUpdates()

        /*
        Log.i(TAG, "duva: sync building worker")
        var workerData = Data.Builder()
            .putString("uid", "0")
            .build()

        if(auth.currentUser != null) {
            Log.d(TAG, "duva: sync user found, sending uid = " + auth.uid + " to worker")
            workerData = Data.Builder()
                .putString("uid", auth.uid)
                .build()
        } else {
            Log.d(TAG, "duva: sync user no Firebase auth object found, sending uid = 0 to worker")
        }

        val constraints = Constraints.Builder()
            .build()
        val workerRequest = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workerData)
            .build()
        //WorkManager.getInstance(this).enqueue(workerRequest)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(UNIQUEWORKSTRING, ExistingPeriodicWorkPolicy.REPLACE, workerRequest)
         */
        WorkManager.getInstance(this).cancelUniqueWork(UNIQUEWORKSTRING)
        WorkManager.getInstance(this).cancelAllWork()
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
        if(auth.currentUser != null && currentUser != null && currentLocation != null) {
            Firestore.updateField("users", currentUser!!.id, "lastLocation", currentLocation, object: FirestoreCallback {
                override fun onSuccess() {}
                override fun onFailed() {}
            })
        }
    }

    override fun onPause() {
        super.onPause()
        if(auth.currentUser != null && currentUser != null && currentLocation != null) {
            Firestore.updateField("users", currentUser!!.id, "lastLocation", currentLocation, object: FirestoreCallback {
                override fun onSuccess() {}
                override fun onFailed() {}
            })
        }
        //stopLocationUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        ForegroundService.stopService(this)


        /*
        if(Globals.permissionsGranted) {
            unregisterReceiver(br)
        }
        */
    }

    // TODO: update this, placeholder for auth callback
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "duva: in onActivityResult")

        Log.i(TAG, "duva: requestCode == 200 in onActivityResult")
        if(Globals.permissionsGranted) {
            auth = FirebaseAuth.getInstance()
            if(auth.currentUser != null) {
                setupUser()
                setupSubscriptions()
                setupZones()
            }
        }
    }

    fun startLocationUpdates() {
        Log.i(TAG, "duva: in startLocationUpdates()")
        if(checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            locationRequest = LocationRequest().apply {
                interval = 60 * 1000
                fastestInterval = 20 * 1000
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }

            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
            val client: SettingsClient = LocationServices.getSettingsClient(this)
            val task: Task<LocationSettingsResponse> = client.checkLocationSettings(builder.build())

            task.addOnSuccessListener { locationSettingsResponse ->
                Log.i(TAG, "duva: locationSettingsResponse task successful")
            }

            task.addOnFailureListener { exception ->
                if(exception is ResolvableApiException) {
                    try {
                        //exception.startResolutionForResult(this@MainActivity, REQUEST_CHECK_SETTINGS)
                    } catch (sendEx: IntentSender.SendIntentException) {
                        Log.d(TAG, "duva: locationSettingsResponse failed")
                    }
                }
            }

            registerLocationListener()
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
            Log.i(TAG, "duva: Location updates started")
        }
    }

    private fun registerLocationListener() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                locationResult ?: return
                //Log.i(TAG, "duva: geofence location in onLocationResult()")
                for(location in locationResult.locations) {
                    //Log.i(TAG, "duva: location geofence looping in registerLocationListener()")
                    currentLocation = GeoPoint(location.latitude, location.longitude)
                    Globals.currentLocation = GeoPoint(location.latitude, location.longitude)
                    /*
                    if(Globals.currentUser != null && Globals.currentUser!!.id != "0") {
                        Firestore.updateField("users", Globals.currentUser!!.id, "lastLocation", Globals.currentLocation, object: FirestoreCallback {
                            override fun onSuccess() {}
                            override fun onFailed() {}
                        })
                    }
                    */

                    Log.i(TAG, "duva: location geofence currentLocation = " + currentLocation.toString())
                    //googleMapInterface?.onLocationUpdate(GeoPoint(location.latitude, location.longitude))
                }
            }
        }
    }

    private fun stopLocationUpdates() {
        //fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.i(TAG, "Location updates stopped")
    }

    private fun setMapListener(listener: GoogleMapInterface) {
        this.googleMapInterface = listener
    }

    public fun setupUser() {
        Firestore.userListener(auth.currentUser, object: FirestoreListener<User> {
            override fun onStart() {
                //Something
            }

            override fun onSuccess(obj: User) {
                Log.i(TAG, "duva: user populated user object in setupUser()")
                currentUser = obj
                Globals.currentUser = obj
                updateHomeFragment()
                //setupSubscriptions()
            }

            override fun onFailed() {
                // Something
            }
        })
    }

    public fun setupSubscriptions() {
        Firestore.subscriptionsListener(auth.currentUser, object: FirestoreListener<MutableList<Subscription>> {
            override fun onStart() {
                // Something
            }

            override fun onSuccess(obj: MutableList<Subscription>) {
                currentSubscriptions = obj
                Globals.currentSubscriptions = obj
                updateHomeFragment()

                for(subscription in obj) {
                    Log.i(TAG, "duva: subscription found = " + subscription.zone + " for user = " + currentUser?.uid + " with auth.currentUser.email = " + auth.currentUser?.email)
                }
                //setupZones()

            }

            override fun onFailed() {
                // Something
            }
        })
    }

    public fun setupZones() {
        Firestore.zonesListener(object: FirestoreListener<MutableList<Zone>> {
            override fun onStart() {
                // Something
            }

            override fun onSuccess(obj: MutableList<Zone>) {
                allZones = obj
                Globals.allZones = obj
                Firestore.unsubscribeFromAllTopics(obj)

                // This should be rewritten noasync so we can be sure all the topics are unsubscribed from before we re-subscribe topics from database
                Globals.currentSubscriptions?.forEach { subscription ->
                    Firestore.subscribeToTopic(subscription.zone, object: FirestoreCallback {
                        override fun onSuccess() {
                            Log.i(TAG, "duva: user zone subscribe to stored subscription in database = ${subscription.zone}")
                        }

                        override fun onFailed() {
                            Log.d(TAG, "duva: user zone subscribe failed from database for ${subscription.zone}")
                        }
                    })
                }

                setupGeofences(obj)
            }

            override fun onFailed() {
                // Something
            }
        })
    }

    public fun setupGeofences(zones: MutableList<Zone>) {
        for(zone in zones) {
            Log.i(TAG, "duva: geofence zone found = " + zone.name)
            geofenceList.add(Geofence.Builder()
                .setRequestId(zone.id)
                .setCircularRegion(zone.location.latitude, zone.location.longitude, zone.radius.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setLoiteringDelay(20 * 1000)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build())
        }

        Log.i(TAG, "duva: geofence list = " + geofenceList.toString())

        if(checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Log.i(TAG, "duva: geofence adding or removing geofences")
            if(this::geofencingClient.isInitialized) {
                geofencingClient.addGeofences(getGeofencingRequest(), geofencePendingIntent)?.run {
                    addOnSuccessListener {
                        Log.i(TAG, "duva: geofence added, geofencePendingIntent = " + geofencePendingIntent.toString())
                        Globals.geofencesAdded = true
                    }
                    addOnFailureListener {
                        Log.d(TAG, "duva: failed to add geofence" + exception.toString())
                        Globals.geofencesAdded = false
                    }
                }
            }
        }
    }

    private fun getGeofencingRequest(): GeofencingRequest {
        Log.i(TAG, "duva: geofence getGeofencingRequest()")
        return GeofencingRequest.Builder().apply {
            setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            addGeofences(geofenceList)
        }.build()
    }

    // TODO: this might trigger uncorrectly since it receives zone[0] id. Fix, sometime.
    private fun geofenceTransition(transition: String, zoneid: String) {
        Log.i(TAG, "duva: geofence received geofenceTransition($transition, $zoneid)")

        when(transition) {
            "enter" -> {
                Log.i(TAG, "duva: geofence geofenceTransition() enter in MainActivity: $zoneid")
                Globals.activeZoneId = zoneid
                updateHomeFragment(zoneid)

                // Separate paths for logged in users since we also update database if logged in
                if(auth.currentUser != null && currentUser != null) {
                    val subscriptionid: String = getSubscriptionidFromZoneid(zoneid)
                    if(subscriptionid != "null") {
                        Firestore.updateField("subscriptions", subscriptionid, "active", true, object: FirestoreCallback {
                            override fun onSuccess() {}
                            override fun onFailed() {}
                        })
                    }

                    Firestore.updateField("users", currentUser!!.id, "lastZone", zoneid, object: FirestoreCallback {
                        override fun onSuccess() {}
                        override fun onFailed() {}
                    })

                    Firestore.updateField("users", currentUser!!.id, "lastUpdate", Timestamp.now(), object: FirestoreCallback {
                        override fun onSuccess() {}
                        override fun onFailed() {}
                    })

                    Log.i(TAG, "duva: FCM just before subscribe")
                    Firestore.subscribeToTopic(zoneid, object: FirestoreCallback {
                        override fun onSuccess() {}
                        override fun onFailed() {}
                    })
                }

                createNotification("Enter", "Zone: " + Globals.getZoneNameFromZoneId(zoneid))
            }
            "dwell" -> {
                Log.i(TAG, "duva: geofence geofenceTransition() dwell in MainActivity: $zoneid")
                Globals.activeZoneId = zoneid
                updateHomeFragment(zoneid)

                // Separate paths for logged in users since we also update database if logged in
                if(auth.currentUser != null && currentUser != null) {
                    val subscriptionid: String = getSubscriptionidFromZoneid(zoneid)
                    if(subscriptionid != null) {
                        Firestore.updateField("subscriptions", subscriptionid, "active", true, object: FirestoreCallback {
                            override fun onSuccess() {}
                            override fun onFailed() {}
                        })
                    }

                    Firestore.updateField("users", currentUser!!.id, "lastZone", zoneid, object: FirestoreCallback {
                        override fun onSuccess() {}
                        override fun onFailed() {}
                    })

                    Firestore.subscribeToTopic(zoneid, object: FirestoreCallback {
                        override fun onSuccess() {}
                        override fun onFailed() {}
                    })
                }
                createNotification("Dwell", "Zone: " + Globals.getZoneNameFromZoneId(zoneid))
            }
            "exit" -> {
                Log.i(TAG, "duva: geofence geofenceTransition() exit in MainActivity: $zoneid")
                Globals.activeZoneId = "0"
                updateHomeFragment("exit")

                if(auth.currentUser != null && currentUser != null) {
                    val subscriptionid: String = getSubscriptionidFromZoneid(zoneid)
                    if(subscriptionid != null) {
                        Firestore.updateField("subscriptions", subscriptionid, "active", false, object: FirestoreCallback {
                            override fun onSuccess() {}
                            override fun onFailed() {}
                        })
                    }

                    val oneSelected: Subscription
                    val selectedSubscription = Globals.currentSubscriptions?.filter { it.zone.equals(zoneid) }
                    if(selectedSubscription != null && selectedSubscription.size == 1) {
                        selectedSubscription?.let {
                            oneSelected = selectedSubscription[0]
                            if(oneSelected.zone.equals(zoneid)) {
                                Log.i(TAG, "duva: FCM geofence DEBUG zone exit equals a subscribed zone in database - keeping topic subscription")
                            } else {
                                Log.i(TAG, "duva: FCM geofence DEBUG zone exit does NOT equal a subscribed zone in the database")
                            }
                        }
                    } else {
                        Log.i(TAG, "duva: FCM geofence DEBUG zone exit didn't find corresponding subscription in database - unsubscribing from topic")
                        Firestore.unsubscribeFromTopic(zoneid, object: FirestoreCallback {
                            override fun onSuccess() {}
                            override fun onFailed() {}
                        })
                    }

                }
                //createNotification("Exit", "Zone: " + Globals.getZoneNameFromZoneId(zoneid))
            }
        }
    }

    fun createNotification(title: String, content: String) {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntentNotification = PendingIntent.getActivity(this, 0, notificationIntent, 0)

        val builder = NotificationCompat.Builder(this, CHANNELID)
            .setSmallIcon(android.R.drawable.ic_media_play, 0)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntentNotification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        with(NotificationManagerCompat.from(this)) {
            notify(1, builder.build())
        }
    }

    fun updateHomeFragment(zoneid: String = "unknown") {
        val fragment = supportFragmentManager.findFragmentByTag("HomeFragment")
        if(fragment != null && fragment is HomeFragment) {
            fragment.updateUI()
            if(zoneid != "unknown") {
                fragment.updateLocation(zoneid)
            }
        }
    }

    private fun getSubscriptionidFromZoneid(zoneid: String): String {
        var subscriptionid: String = "null"
        if(auth.currentUser == null) {
            return "null"
        }

        if(currentSubscriptions != null) {
            for(subscription in currentSubscriptions!!) {
                if(zoneid == subscription.zone) {
                    Log.i(TAG, "duva: found match in getSubscriptionFromZoneid: $zoneid == ${subscription.zone}")
                    subscriptionid = subscription.id
                }
            }
        }

        if(subscriptionid == "null") {
            Log.d(TAG, "duva: no match found in getSubscriptionFromZoneid: $zoneid")
        }

        return subscriptionid
    }

    /**
     * The following two functions, setupPermission() and makeRequest(), were too convoluted for this use case.
     * Instead we request all permissions at once and set a global variable if they pass
     */
    /*
    private fun setupPermission(permissionString: String) {
        val permission = ContextCompat.checkSelfPermission(this, permissionString)

        if(permission != PackageManager.PERMISSION_GRANTED) {
            if(ActivityCompat.shouldShowRequestPermissionRationale(this, permissionString)) {
                val builder = AlertDialog.Builder(this)
                when(permissionString) {
                    Manifest.permission.ACCESS_FINE_LOCATION -> builder.setMessage(R.string.permission_ACCESS_FINE_LOCATION).setTitle(R.string.permission_title)
                    Manifest.permission.WRITE_EXTERNAL_STORAGE -> builder.setMessage(R.string.permission_WRITE_EXTERNAL_STORAGE).setTitle(R.string.permission_title)
                    Manifest.permission.RECORD_AUDIO -> builder.setMessage(R.string.permission_RECORD_AUDIO).setTitle(R.string.permission_title)
                }

                builder.setPositiveButton(R.string.permission_button_ok) { dialog, id ->
                    Log.i(TAG, "Permission ok button clicked")
                    makeRequest(permissionString)
                }

                val dialog = builder.create()
                dialog.show()
            } else {
                makeRequest(permissionString)
            }
        }
    }

    private fun makeRequest(permissionString: String) {
        var requestCode = 100

        when(permissionString) {
            Manifest.permission.ACCESS_FINE_LOCATION -> requestCode = requestCodeAccessFineLocation
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> requestCode = requestCodeWriteExternalStorage
            Manifest.permission.RECORD_AUDIO -> requestCode = requestCodeRecordAudio
        }

        ActivityCompat.requestPermissions(this, arrayOf(permissionString), requestCode)
    }
    */

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        /*
        when(requestCode) {
            requestCodeAccessFineLocation -> {
                if(grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Permission: " + permissions[0] + "has been granted")
                } else {
                    Log.d(TAG, "Permission: " + permissions[0] + "has been denied")
                    setupUser()
                    setupSubscriptions()
                    setupZones()
                }
            }
            requestCodeWriteExternalStorage -> {
                if(grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    // Keep for future use
                } else {
                    // Keep for future use
                }
            }
            requestCodeRecordAudio -> {
                if(grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    // Keep for future use
                } else {
                    // Keep for future use
                }
            }
        }

        */

        val firestore = FirebaseFirestore.getInstance()
        val settings = FirebaseFirestoreSettings.Builder()
            .setTimestampsInSnapshotsEnabled(true)
            .build()
        firestore.firestoreSettings = settings

        geofencingClient = LocationServices.getGeofencingClient(this)
        LocalBroadcastManager.getInstance(this).registerReceiver(br, filter)

        checkPermissionsAndInitialize()
        navigation.setOnNavigationItemSelectedListener(mOnNavigationItemSelectedListener)
        switchFragment(HomeFragment(), "HomeFragment")
    }

    private fun checkPermission(permissionString: String): Boolean = ContextCompat.checkSelfPermission(this, permissionString) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel)
            val descriptionText = getString(R.string.notification_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNELID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.i(TAG, "duva: notification channel created")
        }
    }
}