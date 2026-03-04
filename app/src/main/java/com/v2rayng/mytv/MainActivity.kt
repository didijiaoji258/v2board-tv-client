package com.v2rayng.mytv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.v2rayng.mytv.ui.home.MainFragment
import com.v2rayng.mytv.ui.nodes.ServerListFragment
import com.v2rayng.mytv.ui.pair.PairingFragment

class MainActivity : FragmentActivity(),
    MainFragment.NavigationCallback,
    ServerListFragment.Callback {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Þ¥ Application B„ HTTP Server Þ
        val app = application as TvApplication
        app.httpServer?.onPaired = {
            handler.post {
                Log.i(TAG, "Pair success, navigating to main")
                showMain()
            }
        }

        if (savedInstanceState == null) {
            navigateByState()
        }
    }

    override fun onResume() {
        super.onResume()
        // Ï! Resume Í°ÑšÞ2b Activity Íú"1	
        val app = application as TvApplication
        app.httpServer?.onPaired = {
            handler.post {
                Log.i(TAG, "Pair success (onResume), navigating to main")
                showMain()
            }
        }
    }

    private fun navigateByState() {
        val ds = (application as TvApplication).dataStore
        when {
            !ds.isPaired -> showPairing(expired = false)
            ds.isExpired -> showPairing(expired = true)
            else -> showMain()
        }
    }

    private fun showPairing(expired: Boolean) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, PairingFragment.newInstance(expired))
            .commit()
    }

    private fun showMain() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, MainFragment())
            .commit()
    }

    // MainFragment.NavigationCallback
    override fun onNavigateToServerList() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, ServerListFragment())
            .addToBackStack(null)
            .commit()
    }

    override fun onNavigateToPairing() {
        showPairing(expired = false)
    }

    // ServerListFragment.Callback
    override fun onServerSelected() {
        supportFragmentManager.popBackStack()
    }

    override fun onBackFromServerList() {
        supportFragmentManager.popBackStack()
    }
}