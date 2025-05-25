package com.example.ecotumbler

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.ecotumbler.databinding.ActivityHomeBinding
import com.google.firebase.database.*

class Home : AppCompatActivity() {
    private var isSpinning = false
    private lateinit var database: DatabaseReference
    private lateinit var heartbeatRef: DatabaseReference
    private lateinit var heartbeatListener: ValueEventListener
    private lateinit var binding: ActivityHomeBinding

    // Declare a Handler & Runnable for countdown updates
    private val countdownHandler = Handler(Looper.getMainLooper())
    private lateinit var countdownRunnable: Runnable

    // Suppose this is your target time in milliseconds (Epoch time) -- set this based on schedule
    private var targetTimeMs: Long = 0

    // track last heartbeat arrival
    private var lastHeartbeatMs = 0L
    private val CHECK_INTERVAL_MS   = 1_000L  // check every 1 s
    private val STALE_THRESHOLD_MS = 5_000L  // if no heartbeat for 5 s → N/A

    // handler for periodic stale‐check
    private val handler = Handler(Looper.getMainLooper())
    private val staleChecker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - lastHeartbeatMs > STALE_THRESHOLD_MS) {
                binding.temperature.text = "N/A"
                binding.humidity.text    = "N/A"
                binding.connection.text  = "DISCONNECTED"
                binding.dot.setImageResource(R.drawable.ic_dot2)
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }

    }
    private fun openAppOrPlayStore(packageName: String) {
        val packageManager = packageManager
        val intent = packageManager.getLaunchIntentForPackage(packageName)

        if (intent != null) {
            // App is installed → open it
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            // App not installed → open Play Store
            try {
                val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                playStoreIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(playStoreIntent)
            } catch (e: ActivityNotFoundException) {
                // If Play Store app not available, open in browser
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
                startActivity(browserIntent)
            }
        }
    }
    private fun startCountdown(targetMillis: Long) {
        targetTimeMs = targetMillis

        countdownRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val diff = targetTimeMs - now

                if (diff > 0) {
                    val seconds = diff / 1000
                    val days = seconds / (24 * 3600)
                    val hours = (seconds % (24 * 3600)) / 3600
                    val minutes = (seconds % 3600) / 60
                    // Optionally: val secs = seconds % 60

                    binding.days.text = days.toString().padStart(2, '0')
                    binding.hours.text = hours.toString().padStart(2, '0')
                    binding.minutes.text = minutes.toString().padStart(2, '0')

                    countdownHandler.postDelayed(this, 1000)
                } else {
                    // Countdown finished
                    binding.days.text = "00"
                    binding.hours.text = "00"
                    binding.minutes.text = "00"
                }
            }
        }
        countdownHandler.post(countdownRunnable)
    }

    fun calculateTargetTime(count: Int, unit: String): Long {
        val now = System.currentTimeMillis()
        val millis = when(unit) {
            "Day(s)" -> count * 24L * 60 * 60 * 1000
            "Week(s)" -> count * 7L * 24 * 60 * 60 * 1000
            "Month(s)" -> count * 30L * 24 * 60 * 60 * 1000 // Approximate month as 30 days
            else -> 0L
        }
        return now + millis
    }
    private fun loadScheduleFromFirebase() {
        val scheduleRef = FirebaseDatabase.getInstance().getReference("Schedule")
        scheduleRef.get().addOnSuccessListener { dataSnapshot ->
            val savedDays = dataSnapshot.child("Days").value as? List<*>
            val savedCount = dataSnapshot.child("Count").value?.toString()?.toIntOrNull() ?: 0
            val savedUnit = dataSnapshot.child("Unit").value?.toString() ?: "Day"
            val startTimestamp = dataSnapshot.child("StartTimestamp").value?.toString()?.toLongOrNull() ?: 0L
            // ✅ Update main screen UI
            val dayBoxMap = mapOf(
                "Sunday" to binding.sun,
                "Monday" to binding.mon,
                "Tuesday" to binding.tue,
                "Wednesday" to binding.wed,
                "Thursday" to binding.thu,
                "Friday" to binding.fri,
                "Saturday" to binding.sat
            )

            val selectedDays = savedDays?.map { it.toString() } ?: emptyList()

            for ((day, layout) in dayBoxMap) {
                if (selectedDays.contains(day)) {
                    layout.setBackgroundResource(R.drawable.f_blue)
                } else {
                    layout.setBackgroundResource(R.drawable.f_day)
                }
            }

            val unitDisplay = when (savedUnit) {
                "Day" -> if (savedCount > 1) "DAYS" else "DAY"
                "Week" -> if (savedCount > 1) "WEEKS" else "WEEK"
                "Month" -> if (savedCount > 1) "MONTHS" else "MONTH"
                else -> savedUnit.uppercase()
            }

            val displayText = "$savedCount $unitDisplay"
            val everyDateTextView = findViewById<TextView>(R.id.every_date)
            everyDateTextView?.text = displayText

            // ⏱ Optional: start countdown based on saved schedule
            val startMs = startTimestamp * 1000
            val durationMs = when (savedUnit) {
                "Day" -> savedCount * 24L * 60 * 60 * 1000
                "Week" -> savedCount * 7L * 24 * 60 * 60 * 1000
                "Month" -> savedCount * 30L * 24 * 60 * 60 * 1000
                else -> 0L
            }

            val targetTime = startMs + durationMs
            startCountdown(targetTime)

        }.addOnFailureListener {
            Toast.makeText(this, "Failed to load saved schedule", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loadScheduleFromFirebase()

        val spinButton    = findViewById<TextView>(R.id.spin_button)
        val spinContainer = findViewById<LinearLayout>(R.id.spin_button_container)

        val toggleSpin: (View) -> Unit = {
            val motorRef = FirebaseDatabase.getInstance().getReference("Motor/state")
            motorRef.get().addOnSuccessListener { snapshot ->
                val currentState = snapshot.getValue(Boolean::class.java) ?: false
                val newState     = !currentState
                motorRef.setValue(newState)
                isSpinning = newState
                if (isSpinning) {
                    binding.spinButton.text = "STOP SPIN"
                    binding.spinButtonContainer
                        .setBackgroundResource(R.drawable.f_red)
                } else {
                    binding.spinButton.text = "START SPIN"
                    binding.spinButtonContainer
                        .setBackgroundResource(R.drawable.f_blue)
                }
            }.addOnFailureListener {
                Toast.makeText(this, "Failed to read motor state", Toast.LENGTH_SHORT).show()
            }
        }
        spinButton.setOnClickListener(toggleSpin)
        spinContainer.setOnClickListener(toggleSpin)

        binding.connectButton.setOnClickListener {
            openAppOrPlayStore("com.espressif.provble")
        }
        binding.connectContainer.setOnClickListener {
            openAppOrPlayStore("com.espressif.provble")
        }
        binding.edit.setOnClickListener {
            val dialogView = layoutInflater.inflate(R.layout.dialog_edit_id, null)
            val everyDateTextView = findViewById<TextView>(R.id.every_date)
            val currentTimestamp = System.currentTimeMillis() / 1000L

            val monday     = dialogView.findViewById<CheckBox>(R.id.monday)
            val tuesday    = dialogView.findViewById<CheckBox>(R.id.tuesday)
            val wednesday  = dialogView.findViewById<CheckBox>(R.id.wednesday)
            val thursday   = dialogView.findViewById<CheckBox>(R.id.thursday)
            val friday     = dialogView.findViewById<CheckBox>(R.id.friday)
            val saturday   = dialogView.findViewById<CheckBox>(R.id.saturday)
            val sunday     = dialogView.findViewById<CheckBox>(R.id.sunday)

            val inputCount = dialogView.findViewById<EditText>(R.id.input_count)
            val spinner    = dialogView.findViewById<Spinner>(R.id.unit_spinner)

            val units = listOf("Day(s)", "Week(s)", "Month(s)")
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
            spinner.adapter = adapter

            val scheduleRef = FirebaseDatabase.getInstance().getReference("Schedule")
            scheduleRef.get().addOnSuccessListener { dataSnapshot ->
                val savedDays = dataSnapshot.child("Days").value as? List<*>
                val savedCount = dataSnapshot.child("Count").value?.toString()?.toIntOrNull() ?: 0
                val savedUnit = dataSnapshot.child("Unit").value?.toString() ?: "Day"

                // Set checked days
                savedDays?.forEach {
                    when (it.toString()) {
                        "Monday"    -> monday.isChecked = true
                        "Tuesday"   -> tuesday.isChecked = true
                        "Wednesday" -> wednesday.isChecked = true
                        "Thursday"  -> thursday.isChecked = true
                        "Friday"    -> friday.isChecked = true
                        "Saturday"  -> saturday.isChecked = true
                        "Sunday"    -> sunday.isChecked = true
                    }
                }
                inputCount.setText(savedCount.toString())

                val spinnerIndex = when (savedUnit) {
                    "Day" -> 0
                    "Week" -> 1
                    "Month" -> 2
                    else -> 0
                }
                spinner.setSelection(spinnerIndex)

                // After loading Firebase values — show the dialog
                AlertDialog.Builder(this)
                    .setTitle("Set Repeat Schedule")
                    .setView(dialogView)
                    .setPositiveButton("Save") { _, _ ->
                        // Your existing save logic goes here
                        val dayBoxMap = mapOf(
                            "Sunday" to binding.sun,
                            "Monday" to binding.mon,
                            "Tuesday" to binding.tue,
                            "Wednesday" to binding.wed,
                            "Thursday" to binding.thu,
                            "Friday" to binding.fri,
                            "Saturday" to binding.sat
                        )

                        val checkedDays = mapOf(
                            "Sunday" to sunday.isChecked,
                            "Monday" to monday.isChecked,
                            "Tuesday" to tuesday.isChecked,
                            "Wednesday" to wednesday.isChecked,
                            "Thursday" to thursday.isChecked,
                            "Friday" to friday.isChecked,
                            "Saturday" to saturday.isChecked
                        )

                        for ((day, layout) in dayBoxMap) {
                            if (checkedDays[day] == true) {
                                layout.setBackgroundResource(R.drawable.f_blue)
                            } else {
                                layout.setBackgroundResource(R.drawable.f_day)
                            }
                        }

                        val count = inputCount.text.toString().toIntOrNull() ?: 0
                        val unit = spinner.selectedItem?.toString() ?: "Day(s)"
                        val selectedDays = checkedDays.filter { it.value }.keys
                        val unitDisplay = when(unit) {
                            "Day(s)" -> if (count > 1) "DAYS" else "DAY"
                            "Week(s)" -> if (count > 1) "WEEKS" else "WEEK"
                            "Month(s)" -> if (count > 1) "MONTHS" else "MONTH"
                            else -> unit.toUpperCase()
                        }

                        val targetTime = calculateTargetTime(count, unit)
                        startCountdown(targetTime)

                        val displayText = "$count $unitDisplay"
                        everyDateTextView.text = displayText

                        val unitFormatted = when (unit) {
                            "Day(s)" -> "Day"
                            "Week(s)" -> "Week"
                            "Month(s)" -> "Month"
                            else -> unit
                        }

                        val scheduleData = mapOf(
                            "Days" to selectedDays.toList(),
                            "Count" to count,
                            "Unit" to unitFormatted,
                            "StartTimestamp" to currentTimestamp
                        )

                        scheduleRef.setValue(scheduleData)
                            .addOnSuccessListener {
                                Toast.makeText(this, "Schedule saved", Toast.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener {
                                Toast.makeText(this, "Failed to save schedule", Toast.LENGTH_SHORT).show()
                            }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            }.addOnFailureListener {
                Toast.makeText(this, "Failed to load existing schedule", Toast.LENGTH_SHORT).show()
            }
        }
        databaseListener()
        motorStateListener()

        // 1) Heartbeat listener
        heartbeatRef = FirebaseDatabase.getInstance()
            .getReference("heartbeat/lastSeen")
        heartbeatListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastHeartbeatMs = System.currentTimeMillis()
                binding.connection.text = "CONNECTED"
                binding.dot.setImageResource(R.drawable.ic_dot)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        heartbeatRef.addValueEventListener(heartbeatListener)
        // start periodic stale‐check
        handler.postDelayed(staleChecker, CHECK_INTERVAL_MS)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // stop the checker and remove the listener
        handler.removeCallbacks(staleChecker)
        heartbeatRef.removeEventListener(heartbeatListener)
        countdownHandler.removeCallbacks(countdownRunnable)
    }

    private fun databaseListener() {
        database = FirebaseDatabase.getInstance().getReference("Sensor")
        val postListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // only update if ESP32 has sent a heartbeat recently
                if (System.currentTimeMillis() - lastHeartbeatMs <= STALE_THRESHOLD_MS) {
                    val humidity = snapshot.child("humidity_data")
                        .getValue(Float::class.java)
                        ?: 0f
                    val temp = snapshot.child("temp_data")
                        .getValue(Float::class.java)
                        ?: 0f
                    binding.humidity.text    = "$humidity%"
                    binding.temperature.text = "$temp°C"
                }
                // else: do nothing, staleChecker will have set "N/A"
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(
                    this@Home,
                    "FAILED to read sensor data",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        database.addValueEventListener(postListener)
    }

    private fun motorStateListener() {
        val motorRef = FirebaseDatabase.getInstance().getReference("Motor/state")
        motorRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val state = snapshot.getValue(Boolean::class.java) ?: false
                isSpinning = state
                if (isSpinning) {
                    binding.spinButton.text = "STOP SPIN"
                    binding.spinButtonContainer
                        .setBackgroundResource(R.drawable.f_red)
                } else {
                    binding.spinButton.text = "START SPIN"
                    binding.spinButtonContainer
                        .setBackgroundResource(R.drawable.f_blue)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(
                    this@Home,
                    "Failed to read motor state",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }
}
