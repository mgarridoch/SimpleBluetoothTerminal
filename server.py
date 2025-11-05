from bluedot.btcomm import BluetoothServer
import RPi.GPIO as GPIO
import threading  # ¡Importante para los temporizadores!

# --- Configuración de Hardware ---
PENDING_PIN = 23    # LED 1: "Pendiente" (Se enciende al recibir comando)
COOKING_PIN = 24    # LED 2: "Cocinando" (Se enciende 3 min antes de terminar)
READY_PIN = 25      # LED 3: "Listo" (Se enciende al final)
hardware_ready = False

# Variables globales para guardar nuestros temporizadores
# Esto nos permite cancelarlos si recibimos "STOP"
cooking_timer = None
finish_timer = None

try:
    GPIO.setmode(GPIO.BCM)
    # Configurar los 3 pines como salida y apagados
    GPIO.setup(PENDING_PIN, GPIO.OUT)
    GPIO.setup(COOKING_PIN, GPIO.OUT)
    GPIO.setup(READY_PIN, GPIO.OUT)
    
    GPIO.output(PENDING_PIN, GPIO.LOW)
    GPIO.output(COOKING_PIN, GPIO.LOW)
    GPIO.output(READY_PIN, GPIO.LOW)
    
    print("¡GPIO listo para 3 LEDs!")
    hardware_ready = True
except Exception as e:
    print(f"Error al iniciar GPIO: {e}. Corriendo en modo simulación.")

# --- Funciones de los Temporizadores ---
def start_cooking():
    """Esta función se llama cuando faltan 3 minutos."""
    print("TEMPORIZADOR: Faltan 3 minutos. Encendiendo LED Cocinando (Pin 24).")
    if hardware_ready:
        GPIO.output(COOKING_PIN, GPIO.HIGH)
    else:
        print("SIM: LED Cocinando ENCENDIDO")

def finish_breakfast():
    """Esta función se llama cuando el tiempo total se cumple."""
    print("TEMPORIZADOR: ¡Desayuno listo! Encendiendo LED Listo (Pin 25).")
    if hardware_ready:
        GPIO.output(PENDING_PIN, GPIO.LOW)  # Apagar "Pendiente"
        GPIO.output(COOKING_PIN, GPIO.LOW)  # Apagar "Cocinando"
        GPIO.output(READY_PIN, GPIO.HIGH)   # Encender "Listo"
    else:
        print("SIM: LED Pendiente APAGADO")
        print("SIM: LED Cocinando APAGADO")
        print("SIM: LED Listo ENCENDIDO")

# --- Lógica de Comandos ---
def on_data_received(data):
    """
    Esta función se llamará cada vez que llegue data
    desde la app Bluetooth Terminal.
    """
    global cooking_timer, finish_timer
    
    command = data.strip() # Limpiar espacios en blanco o saltos de línea
    print(f"Comando recibido: '{command}'")

    # --- Lógica para START X ---
    if command.startswith("START "):
        # Cancelar timers anteriores si es que existen
        if cooking_timer:
            cooking_timer.cancel()
        if finish_timer:
            finish_timer.cancel()
            
        try:
            # Separar "START" del número "5"
            parts = command.split(" ")
            total_minutes = int(parts[1])
            total_seconds = total_minutes * 60
            
            # Calcular cuándo debe empezar a "cocinar" (3 min antes del final)
            # (total_minutes - 3) * 60
            cooking_delay_seconds = (total_minutes - 1
                                     ) * 60
            
            # Si el tiempo total es < 3 min, la cocción empieza inmediatamente
            if cooking_delay_seconds < 0:
                cooking_delay_seconds = 0 
                
            print(f"Comando reconocido. Tiempo total: {total_minutes} min.")
            print(f"La cocción (LED 24) empezará en {cooking_delay_seconds} seg.")
            print(f"El desayuno (LED 25) estará listo en {total_seconds} seg.")

            # --- Iniciar la secuencia de LEDs ---
            if hardware_ready:
                # 1. Encender LED "Pendiente" (Pin 23)
                print("Encendiendo LED Pendiente (Pin 23)...")
                GPIO.output(PENDING_PIN, GPIO.HIGH)
                # Apagar "Listo" (Pin 25) por si estaba prendido
                GPIO.output(READY_PIN, GPIO.LOW)
            else:
                print("SIM: LED Pendiente ENCENDIDO")
                print("SIM: LED Listo APAGADO")

            # 2. Programar el temporizador para "Cocinando"
            cooking_timer = threading.Timer(cooking_delay_seconds, start_cooking)
            cooking_timer.start()
            
            # 3. Programar el temporizador para "Listo"
            finish_timer = threading.Timer(total_seconds, finish_breakfast)
            finish_timer.start()

        except Exception as e:
            print(f"Error procesando comando START: {e}. Formato debe ser 'START [minutos]'")
            
    # --- Lógica para STOP ---
    elif command == "STOP":
        print("¡Comando STOP reconocido! Cancelando todo.")
        
        # Cancelar temporizadores pendientes
        if cooking_timer:
            cooking_timer.cancel()
        if finish_timer:
            finish_timer.cancel()
            
        if hardware_ready:
            # Apagar todos los LEDs
            print("Apagando todos los LEDs...")
            GPIO.output(PENDING_PIN, GPIO.LOW)
            GPIO.output(COOKING_PIN, GPIO.LOW)
            GPIO.output(READY_PIN, GPIO.LOW)
        else:
            print("SIM: Todos los LEDs APAGADOS")

# --- Iniciar el Servidor ---
print("Iniciando servidor Bluetooth Clásico (SPP)...")
print("Asegúrate de que la RPi esté VINCULADA al teléfono.")
print("Esperando conexión desde la app 'Bluetooth Terminal'...")

# "on_data_received" es la función que se ejecutará
# cada vez que lleguen datos.
s = BluetoothServer(on_data_received)

try:
    # Mantiene el script vivo
    # El servidor se ejecuta en segundo plano
    import pause
    pause.indefinitely()
except KeyboardInterrupt:
    print("\nCerrando servidor.")
    if hardware_ready:
        GPIO.cleanup()
        print("GPIO limpiado.")
except ImportError:
    # Si 'pause' no está instalado, usa un bucle
    print("Librería 'pause' no encontrada. Usando bucle infinito.")
    print("Presiona Ctrl+C para salir.")
    while True:
        pass