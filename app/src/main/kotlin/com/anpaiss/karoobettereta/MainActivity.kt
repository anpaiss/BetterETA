package com.anpaiss.karoobettereta

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * Schermata informativa dell'estensione.
 * Nessuna configurazione richiesta — tutto è automatico.
 *
 * Futuri miglioramenti: mostrare il percorso caricato, i bucket
 * velocità appresi, e la precisione della stima.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnOpenSimulator).setOnClickListener {
            startActivity(Intent(this, SimulatorActivity::class.java))
        }
    }
}
