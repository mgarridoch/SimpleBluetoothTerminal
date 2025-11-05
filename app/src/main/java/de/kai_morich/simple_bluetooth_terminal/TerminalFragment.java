package de.kai_morich.simple_bluetooth_terminal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
// --- IMPORTACIONES MODIFICADAS ---
import android.os.Handler;
import android.os.Looper;
// --- FIN MODIFICADAS ---
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
// Importamos los nuevos elementos de la UI
import android.widget.Button;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Calendar; // <-- AÑADIDO

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected {False, Pending, True}

    private String deviceAddress;
    private SerialService service;

    // --- Elementos de UI Antiguos (Eliminados) ---
    // private TextView receiveText;
    // private TextView sendText;
    // private TextUtil.HexWatcher hexWatcher;

    // --- NUEVOS Elementos de UI ---
    private TimePicker alarmTimePicker;
    private NumberPicker waitTimePicker;
    private Button setAlarmButton;
    private Button stopButton;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    // private boolean hexEnabled = false; // Ya no necesitamos esto
    // private String newline = TextUtil.newline_crlf; // Ya no necesitamos esto

    // --- NUEVO: Handler para el temporizador ---
    private Handler alarmHandler;
    private Runnable alarmRunnable;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
        // Inicializamos el Handler
        alarmHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        // Limpiamos el temporizador si salimos
        if(alarmHandler != null && alarmRunnable != null) {
            alarmHandler.removeCallbacks(alarmRunnable);
        }
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if (service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try {
            getActivity().unbindService(this);
        } catch (Exception ignored) {
        }
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if (initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Usamos el layout que modificaste (fragment_terminal.xml)
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);

        // --- Buscamos los NUEVOS elementos ---
        alarmTimePicker = view.findViewById(R.id.alarm_time_picker);
        waitTimePicker = view.findViewById(R.id.wait_time_picker);
        setAlarmButton = view.findViewById(R.id.set_alarm_button);
        stopButton = view.findViewById(R.id.stop_button);

        // --- Configuración Inicial ---
        alarmTimePicker.setIs24HourView(true); // Formato 24h

        // Configurar el NumberPicker
        waitTimePicker.setMinValue(0);
        waitTimePicker.setMaxValue(30);
        waitTimePicker.setValue(5); // Valor por defecto de 5 minutos

        // --- Lógica de los Botones ---
        setAlarmButton.setOnClickListener(v -> setAlarm());
        // El botón STOP ahora también cancela la alarma programada
        stopButton.setOnClickListener(v -> {
            send("STOP");
            if(alarmHandler != null && alarmRunnable != null) {
                alarmHandler.removeCallbacks(alarmRunnable);
                Toast.makeText(getActivity(), "Alarma programada cancelada", Toast.LENGTH_SHORT).show();
            }
        });

        return view;
    }

    // --- Lógica de la Alarma (AHORA ES SIMPLE) ---
    private void setAlarm() {
        // 1. Obtener la hora y minutos del TimePicker
        int hour, minute;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            hour = alarmTimePicker.getHour();
            minute = alarmTimePicker.getMinute();
        } else {
            hour = alarmTimePicker.getCurrentHour();
            minute = alarmTimePicker.getCurrentMinute();
        }

        // 2. Obtener los minutos de espera del NumberPicker
        int waitMinutes = waitTimePicker.getValue();
        final String command = "START " + waitMinutes; // 'final' para usarlo en el Runnable
        final String timeString = String.format("%02d:%02d", hour, minute);

        // 3. Configurar un Calendario para la hora de la alarma
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);

        // Si la hora ya pasó hoy, programarla para mañana
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        // 4. Calcular el delay (retraso) en milisegundos
        long targetTimeMs = calendar.getTimeInMillis();
        long currentTimeMs = System.currentTimeMillis();
        long delayMs = targetTimeMs - currentTimeMs;

        // 5. Cancelar cualquier alarma anterior
        if(alarmHandler != null && alarmRunnable != null) {
            alarmHandler.removeCallbacks(alarmRunnable);
        }

        // 6. Crear el "Runnable" (la acción que se ejecutará)
        alarmRunnable = new Runnable() {
            @Override
            public void run() {
                // Esto se ejecuta cuando se cumple el tiempo
                Toast.makeText(getActivity(), "Alarma de las " + timeString + " activada", Toast.LENGTH_LONG).show();
                send(command);
            }
        };

        // 7. Programar el Handler
        alarmHandler.postDelayed(alarmRunnable, delayMs);

        // 8. Informar al usuario
        Toast.makeText(getActivity(), "Alarma BreakFAST programada para las " + timeString, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
        // Ocultamos los botones que ya no usamos
        // menu.findItem(R.id.hex).setVisible(false);
        // menu.findItem(R.id.newline).setVisible(false);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            // "Clear" ahora puede significar enviar "STOP"
            Toast.makeText(getActivity(), "Enviando STOP...", Toast.LENGTH_SHORT).show();
            send("STOP");
            return true;
        } else if (id == R.id.backgroundNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!service.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
                } else {
                    showNotificationSettings();
                }
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("conectando...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e); // <-- ESTA LÍNEA AHORA FUNCIONA
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    // --- FUNCIÓN SEND (SIMPLIFICADA) ---
    // Esta es la función que envía el comando a la RPi
    private void send(String str) {
        if (connected != Connected.True) {
            Toast.makeText(getActivity(), "no conectado", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // Añadimos un salto de línea "\n" para que bluedot lo reconzca
            String msg = str + "\n";
            byte[] data = msg.getBytes();
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    // --- FUNCIONES RECEIVE y STATUS (MODIFICADAS) ---
    // Ya no tenemos el 'receiveText', así que solo imprimimos a la consola
    private void receive(ArrayDeque<byte[]> datas) {
        for (byte[] data : datas) {
            String msg = new String(data);
            System.out.println("BT_RECEIVE: " + msg);
        }
    }

    private void status(String str) {
        // En lugar de escribir en la app, mostramos un "Toast"
        Toast.makeText(getActivity(), str, Toast.LENGTH_SHORT).show();
        System.out.println("BT_STATUS: " + str);
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", getActivity().getPackageName());
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (Arrays.equals(permissions, new String[]{Manifest.permission.POST_NOTIFICATIONS}) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service.areNotificationsEnabled())
            showNotificationSettings();
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("¡Conectado!");
        connected = Connected.True;
    }

    // --- MÉTODO FALTANTE AÑADIDO (CORRIGE ERROR 2) ---
    @Override
    public void onSerialConnectError(Exception e) {
        status("Conexión fallida: " + e.getMessage());
        disconnect();
    }

    // --- MÉTODO FALTANTE AÑADIDO (CORRIGE ERROR 1) ---
    @Override
    public void onSerialRead(byte[] data) {
        // Simplemente pasa los datos al otro método onSerialRead
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas); // Llama a la función 'receive' que ya modificamos
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("Conexión perdida: " + e.getMessage());
        disconnect();
    }

    @Override // Este método ya estaba, lo moví para agrupar
    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }
}

