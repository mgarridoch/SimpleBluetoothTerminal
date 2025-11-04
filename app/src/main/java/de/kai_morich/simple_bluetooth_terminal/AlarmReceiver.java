package de.kai_morich.simple_bluetooth_terminal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class AlarmReceiver extends BroadcastReceiver {

    // Esta función es llamada por el AlarmManager del sistema
    @Override
    public void onReceive(Context context, Intent intent) {

        // 1. Recuperamos el comando que guardamos (ej. "START 5")
        String command = intent.getStringExtra("command");
        if (command == null) {
            return;
        }

        // 2. Creamos un "Intent" para nuestro SerialService
        //    (El servicio que maneja la conexión Bluetooth)
        Intent serviceIntent = new Intent(context, SerialService.class);

        // 3. Le ponemos una "bandera" especial para que el servicio
        //    sepa qué hacer
        serviceIntent.setAction(Constants.INTENT_ACTION_SEND); // Crearemos esta constante
        serviceIntent.putExtra("command", command); // Le pasamos el comando

        // 4. Iniciamos el servicio para que envíe el comando
        context.startService(serviceIntent);

        // (Opcional) Muestra un Toast para saber que funcionó
        Toast.makeText(context, "BreakFAST: ¡Enviando comando!", Toast.LENGTH_LONG).show();
    }
}