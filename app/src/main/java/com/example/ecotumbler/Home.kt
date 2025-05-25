package com.example.ecotumbler

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        databaseListener()
        motorStateListener()

        // 1) Heartbeat listener
        heartbeatRef = FirebaseDatabase.getInstance()
            .getReference("heartbeat/lastSeen")
        heartbeatListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastHeartbeatMs = System.currentTimeMillis()
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
