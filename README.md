# BastionMCRTP 1.0.0 — BASTION MC

Proyecto fuente para Purpur/Paper 1.21.11 y Java 21.

## Compilar con GitHub
1. Sube **todo el contenido** de este ZIP a la raíz de un repositorio de GitHub (incluida la carpeta `.github`).
2. Ve a Actions → Build BastionMCRTP → Run workflow (o haz push).
3. Cuando termine correctamente, descarga el artifact `BastionMCRTP-JAR`, descomprímelo y pon el `.jar` en `plugins/`.
4. Reinicia el servidor.

## Configurar la cascada
Colócate en las esquinas opuestas del volumen de agua y usa `/bastionrtp pos1`, `/bastionrtp pos2`, `/bastionrtp setportal`. El menú se abre al entrar en la región y pisar agua. Para reactivarlo hay que salir y volver a entrar. `/bastionrtp menu` permite probar el menú como OP.

## Personalizar
Archivos `plugins/BastionMCRTP/config.yml`, `messages.yml`, `menus.yml`. Comprueba los nombres de mundos. MiniMessage y gradientes compatibles en mensajes y menú.

## Límites de esta primera versión
- **No** incluye integración con WorldGuard/GriefPrevention/Lands; estar lejos del Spawn no garantiza evitar bases o protecciones.
- Comprueba suelo sólido, espacio y ausencia de lava, fuego y otros bloques peligrosos en el punto de llegada.
- Buscar en chunks lejanos puede generar terreno y consumir recursos del hosting. La búsqueda limita intentos y usa carga asíncrona de chunks.
- Se recomienda probar primero en servidor de pruebas. No se ha compilado ni probado aquí contra Purpur.
- Cooldown solo tras teleport exitoso; OP y `bastionrtp.bypasscooldown` lo omiten.

## Permisos
`bastionrtp.admin` (OP), `bastionrtp.use` (todos), `bastionrtp.bypasscooldown` (OP).

## Diagnostico v1.0.1
La busqueda ahora espera cada chunk antes de consumir otro intento. En la consola de Holy Hosting busca `[RTP] Inicio`, `[RTP] EXITO` o `[RTP] SIN DESTINO`; este ultimo indica cuantas ubicaciones fueron rechazadas por terreno, borde, destinos recientes o errores de carga. Si el borde es menor que el rango minimo, amplia el world border o ajusta el rango.

## Optimizaciones 1.0.2
- Maximo 2 busquedas RTP simultaneas (configurable).
- Pausa de 10 ticks entre intentos rechazados (configurable).
- El End usa heightmap en lugar de recorrer verticalmente toda la columna.
- Limite de 200 intentos aunque se configure mas, para evitar carga accidental excesiva.
- Mensaje al jugador cuando las busquedas simultaneas estan ocupadas.
- Los chunks se siguen cargando de forma asincrona y cada busqueda espera a la carga anterior.
- La generacion de terreno nuevo sigue consumiendo CPU/disco; no garantiza cero lag.
