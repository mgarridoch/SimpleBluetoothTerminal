package de.kai_morich.simple_bluetooth_terminal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;

public class AlarmScreenActivity extends AppCompatActivity implements ServiceConnection {

    private String command;
    private SerialService service;
    private boolean isBound = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm_screen); // Usaremos un layout nuevo

        // 1. Esto es lo que hace que la Activity aparezca
        //    sobre la pantalla de bloqueo.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }

        // 2. Obtener el comando (ej. "START 5")
        command = getIntent().getStringExtra("command");

        // 3. Configurar los botones
        Button dismissButton = findViewById(R.id.dismiss_button);
        Button snoozeButton = findViewById(R.id.snooze_button);
        TextView commandText = findViewById(R.id.command_text);

        if(command != null) {
            commandText.setText("Comando a enviar: " + command);
        }

        dismissButton.setOnClickListener(v -> onDismiss());
        snoozeButton.setOnClickListener(v -> onSnooze());

        // 4. Conectarnos al SerialService para poder enviar el comando
        bindService(new Intent(this, SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(this);
            isBound = false;
        }
    }

    private void onDismiss() {
        if (isBound && service != null && command != null) {
            try {
                // ¡Enviamos el comando!
                String msg = command + "\n";
                service.write(msg.getBytes());
                Toast.makeText(this, "¡Comando " + command + " enviado!", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(this, "Error al enviar: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Error: Servicio no conectado.", Toast.LENGTH_SHORT).show();
        }
        finish(); // Cierra la pantalla de alarma
    }

    private void onSnooze() {
        // TODO: Implementar lógica de Snooze
        // (Por ahora, solo cierra la pantalla)
        Toast.makeText(this, "Snooze presionado.", Toast.LENGTH_SHORT).show();
        finish();
    }

    // --- Métodos de ServiceConnection ---
    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        isBound = true;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
        isBound = false;
    }
}
