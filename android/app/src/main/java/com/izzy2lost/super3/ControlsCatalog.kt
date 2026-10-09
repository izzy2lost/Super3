package com.izzy2lost.super3

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Remappable Model 3 inputs, mirroring Src/Inputs/Inputs.cpp. Each entry's [id] is the
 * Supermodel.ini key without its "Input" prefix.
 */
object ControlsCatalog {
    enum class Kind {
        // Digital switch or analog value: accepts buttons, d-pad and half-axes (e.g. JOY1_XAXIS_POS).
        BUTTON,

        // Full axis: only accepts a whole stick/trigger axis (e.g. JOY1_XAXIS).
        AXIS,
    }

    data class InputDef(
        val id: String,
        val label: String,
        val kind: Kind = Kind.BUTTON,
        val player: Int = 1,
    ) {
        val iniKey: String get() = "Input$id"
    }

    data class Group(
        val type: String,
        val title: String,
        val inputs: List<InputDef>,
    )

    private fun b(id: String, label: String, player: Int = 1) = InputDef(id, label, Kind.BUTTON, player)

    private fun a(id: String, label: String, player: Int = 1) = InputDef(id, label, Kind.AXIS, player)

    // Keyed by the <input type="..."> names used in Games.xml.
    val groups: List<Group> =
        listOf(
            Group(
                "common",
                "Common",
                listOf(
                    b("Start1", "P1 Start"),
                    b("Coin1", "P1 Coin"),
                    b("Start2", "P2 Start", 2),
                    b("Coin2", "P2 Coin", 2),
                    b("ServiceA", "Service A"),
                    b("TestA", "Test A"),
                    b("ServiceB", "Service B"),
                    b("TestB", "Test B"),
                ),
            ),
            Group(
                "joystick1",
                "Joystick (P1)",
                listOf(
                    b("JoyUp", "Up"),
                    b("JoyDown", "Down"),
                    b("JoyLeft", "Left"),
                    b("JoyRight", "Right"),
                ),
            ),
            Group(
                "joystick2",
                "Joystick (P2)",
                listOf(
                    b("JoyUp2", "Up", 2),
                    b("JoyDown2", "Down", 2),
                    b("JoyLeft2", "Left", 2),
                    b("JoyRight2", "Right", 2),
                ),
            ),
            Group(
                "fighting",
                "Fighting",
                listOf(
                    b("Punch", "P1 Punch"),
                    b("Kick", "P1 Kick"),
                    b("Guard", "P1 Guard"),
                    b("Escape", "P1 Escape"),
                    b("Punch2", "P2 Punch", 2),
                    b("Kick2", "P2 Kick", 2),
                    b("Guard2", "P2 Guard", 2),
                    b("Escape2", "P2 Escape", 2),
                ),
            ),
            Group(
                "spikeout",
                "Spikeout",
                listOf(
                    b("Shift", "Shift"),
                    b("Beat", "Beat"),
                    b("Charge", "Charge"),
                    b("Jump", "Jump"),
                ),
            ),
            Group(
                "soccer",
                "Soccer",
                listOf(
                    b("ShortPass", "P1 Short Pass"),
                    b("LongPass", "P1 Long Pass"),
                    b("Shoot", "P1 Shoot"),
                    b("ShortPass2", "P2 Short Pass", 2),
                    b("LongPass2", "P2 Long Pass", 2),
                    b("Shoot2", "P2 Shoot", 2),
                ),
            ),
            Group(
                "vehicle",
                "Driving",
                listOf(
                    a("Steering", "Steering (axis)"),
                    b("SteeringLeft", "Steer Left"),
                    b("SteeringRight", "Steer Right"),
                    b("Accelerator", "Accelerator"),
                    b("Brake", "Brake"),
                    b("GearShiftUp", "Shift Up"),
                    b("GearShiftDown", "Shift Down"),
                ),
            ),
            Group(
                "shift4",
                "4-Speed Shifter",
                listOf(
                    b("GearShift1", "Gear 1"),
                    b("GearShift2", "Gear 2"),
                    b("GearShift3", "Gear 3"),
                    b("GearShift4", "Gear 4"),
                    b("GearShiftN", "Neutral"),
                ),
            ),
            Group(
                "vr4",
                "View Buttons",
                listOf(
                    b("VR1", "VR1"),
                    b("VR2", "VR2"),
                    b("VR3", "VR3"),
                    b("VR4", "VR4"),
                ),
            ),
            Group("viewchange", "View Change", listOf(b("ViewChange", "View Change"))),
            Group("handbrake", "Hand Brake", listOf(b("HandBrake", "Hand Brake"))),
            Group(
                "harley",
                "Harley-Davidson",
                listOf(
                    b("RearBrake", "Rear Brake"),
                    b("MusicSelect", "Music Select"),
                ),
            ),
            Group(
                "twin_joysticks",
                "Twin Joysticks",
                listOf(
                    b("TwinJoyTurnLeft", "Macro Turn Left"),
                    b("TwinJoyTurnRight", "Macro Turn Right"),
                    b("TwinJoyForward", "Macro Forward"),
                    b("TwinJoyReverse", "Macro Reverse"),
                    b("TwinJoyStrafeLeft", "Macro Strafe Left"),
                    b("TwinJoyStrafeRight", "Macro Strafe Right"),
                    b("TwinJoyJump", "Macro Jump"),
                    b("TwinJoyCrouch", "Macro Crouch"),
                    b("TwinJoyLeft1", "Left Stick Left"),
                    b("TwinJoyRight1", "Left Stick Right"),
                    b("TwinJoyUp1", "Left Stick Up"),
                    b("TwinJoyDown1", "Left Stick Down"),
                    b("TwinJoyLeft2", "Right Stick Left"),
                    b("TwinJoyRight2", "Right Stick Right"),
                    b("TwinJoyUp2", "Right Stick Up"),
                    b("TwinJoyDown2", "Right Stick Down"),
                    b("TwinJoyShot1", "Left Shot Trigger"),
                    b("TwinJoyShot2", "Right Shot Trigger"),
                    b("TwinJoyTurbo1", "Left Turbo"),
                    b("TwinJoyTurbo2", "Right Turbo"),
                ),
            ),
            Group(
                "analog_joystick",
                "Analog Joystick",
                listOf(
                    a("AnalogJoyX", "X-Axis"),
                    a("AnalogJoyY", "Y-Axis"),
                    b("AnalogJoyLeft", "Left"),
                    b("AnalogJoyRight", "Right"),
                    b("AnalogJoyUp", "Up"),
                    b("AnalogJoyDown", "Down"),
                    b("AnalogJoyTrigger", "Trigger 1"),
                    b("AnalogJoyTrigger2", "Trigger 2"),
                    b("AnalogJoyEvent", "Event 1"),
                    b("AnalogJoyEvent2", "Event 2"),
                ),
            ),
            Group(
                "gun1",
                "Light Gun (P1)",
                listOf(
                    a("GunX", "Aim X-Axis"),
                    a("GunY", "Aim Y-Axis"),
                    b("GunLeft", "Aim Left"),
                    b("GunRight", "Aim Right"),
                    b("GunUp", "Aim Up"),
                    b("GunDown", "Aim Down"),
                    b("Trigger", "Trigger"),
                    b("Offscreen", "Point Off-screen (reload)"),
                ),
            ),
            Group(
                "gun2",
                "Light Gun (P2)",
                listOf(
                    a("GunX2", "Aim X-Axis", 2),
                    a("GunY2", "Aim Y-Axis", 2),
                    b("GunLeft2", "Aim Left", 2),
                    b("GunRight2", "Aim Right", 2),
                    b("GunUp2", "Aim Up", 2),
                    b("GunDown2", "Aim Down", 2),
                    b("Trigger2", "Trigger", 2),
                    b("Offscreen2", "Point Off-screen (reload)", 2),
                ),
            ),
            Group(
                "analog_gun1",
                "Analog Gun (P1)",
                listOf(
                    a("AnalogGunX", "Aim X-Axis"),
                    a("AnalogGunY", "Aim Y-Axis"),
                    b("AnalogGunLeft", "Aim Left"),
                    b("AnalogGunRight", "Aim Right"),
                    b("AnalogGunUp", "Aim Up"),
                    b("AnalogGunDown", "Aim Down"),
                    b("AnalogTriggerLeft", "Left Trigger"),
                    b("AnalogTriggerRight", "Right Trigger"),
                ),
            ),
            Group(
                "analog_gun2",
                "Analog Gun (P2)",
                listOf(
                    a("AnalogGunX2", "Aim X-Axis", 2),
                    a("AnalogGunY2", "Aim Y-Axis", 2),
                    b("AnalogGunLeft2", "Aim Left", 2),
                    b("AnalogGunRight2", "Aim Right", 2),
                    b("AnalogGunUp2", "Aim Up", 2),
                    b("AnalogGunDown2", "Aim Down", 2),
                    b("AnalogTriggerLeft2", "Left Trigger", 2),
                    b("AnalogTriggerRight2", "Right Trigger", 2),
                ),
            ),
            Group(
                "ski",
                "Ski Champ",
                listOf(
                    a("SkiX", "X-Axis"),
                    a("SkiY", "Y-Axis"),
                    b("SkiLeft", "Left"),
                    b("SkiRight", "Right"),
                    b("SkiUp", "Up"),
                    b("SkiDown", "Down"),
                    b("SkiPollLeft", "Pole Left"),
                    b("SkiPollRight", "Pole Right"),
                    b("SkiSelect1", "Select 1"),
                    b("SkiSelect2", "Select 2"),
                    b("SkiSelect3", "Select 3"),
                ),
            ),
            Group(
                "magtruck",
                "Magical Truck",
                listOf(
                    a("MagicalLever1", "P1 Lever (axis)"),
                    b("MagicalLeverUp1", "P1 Lever Up"),
                    b("MagicalLeverDown1", "P1 Lever Down"),
                    b("MagicalPedal1", "P1 Pedal"),
                    a("MagicalLever2", "P2 Lever (axis)", 2),
                    b("MagicalLeverUp2", "P2 Lever Up", 2),
                    b("MagicalLeverDown2", "P2 Lever Down", 2),
                    b("MagicalPedal2", "P2 Pedal", 2),
                ),
            ),
            Group(
                "fishing",
                "Fishing",
                listOf(
                    a("FishingRodX", "Rod X-Axis"),
                    a("FishingRodY", "Rod Y-Axis"),
                    b("FishingRodLeft", "Rod Left"),
                    b("FishingRodRight", "Rod Right"),
                    b("FishingRodUp", "Rod Up"),
                    b("FishingRodDown", "Rod Down"),
                    a("FishingStickX", "Stick X-Axis"),
                    a("FishingStickY", "Stick Y-Axis"),
                    b("FishingStickLeft", "Stick Left"),
                    b("FishingStickRight", "Stick Right"),
                    b("FishingStickUp", "Stick Up"),
                    b("FishingStickDown", "Stick Down"),
                    b("FishingReel", "Reel"),
                    b("FishingCast", "Cast"),
                    b("FishingSelect", "Select"),
                    b("FishingTension", "Tension"),
                ),
            ),
        )

    fun groupsFor(inputTypes: Set<String>?): List<Group> {
        if (inputTypes.isNullOrEmpty()) return groups
        return groups.filter { it.type == "common" || it.type in inputTypes }
    }

    // --- Mapping strings ---------------------------------------------------------------------

    fun splitTokens(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value
            .trim()
            .trim('"')
            .split(',')
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() && !it.equals("NONE", ignoreCase = true) }
    }

    fun joinTokens(tokens: List<String>): String = if (tokens.isEmpty()) "NONE" else tokens.joinToString(",")

    fun isControllerToken(token: String): Boolean = token.uppercase().contains("JOY")

    // --- Friendly names ----------------------------------------------------------------------

    private val buttonNames =
        mapOf(
            1 to "A",
            2 to "B",
            3 to "X",
            4 to "Y",
            5 to "LB",
            6 to "RB",
            7 to "Select",
            8 to "Start",
            9 to "L3",
            10 to "R3",
            13 to "Home",
        )

    private val axisNames =
        mapOf(
            "XAXIS" to "Left Stick X",
            "YAXIS" to "Left Stick Y",
            "RXAXIS" to "Right Stick X",
            "RYAXIS" to "Right Stick Y",
            "ZAXIS" to "LT",
            "RZAXIS" to "RT",
        )

    private val halfAxisNames =
        mapOf(
            "XAXIS_NEG" to "Left Stick ←",
            "XAXIS_POS" to "Left Stick →",
            "YAXIS_NEG" to "Left Stick ↑",
            "YAXIS_POS" to "Left Stick ↓",
            "RXAXIS_NEG" to "Right Stick ←",
            "RXAXIS_POS" to "Right Stick →",
            "RYAXIS_NEG" to "Right Stick ↑",
            "RYAXIS_POS" to "Right Stick ↓",
            "ZAXIS_POS" to "LT",
            "RZAXIS_POS" to "RT",
        )

    private val tokenRx = Regex("^JOY(\\d*)_(.+)$", RegexOption.IGNORE_CASE)

    fun describeToken(token: String): String {
        val m = tokenRx.matchEntire(token.trim()) ?: return token
        val joy = m.groupValues[1]
        var part = m.groupValues[2].uppercase()
        var inverted = false
        if (part.endsWith("_INV")) {
            inverted = true
            part = part.removeSuffix("_INV")
        }
        val name =
            when {
                part.startsWith("BUTTON") -> {
                    val n = part.removePrefix("BUTTON").toIntOrNull()
                    n?.let { buttonNames[it] ?: "Button $it" } ?: part
                }
                part.startsWith("POV") -> {
                    when (part.substringAfterLast('_')) {
                        "UP" -> "D-pad ↑"
                        "DOWN" -> "D-pad ↓"
                        "LEFT" -> "D-pad ←"
                        "RIGHT" -> "D-pad →"
                        else -> part
                    }
                }
                else -> halfAxisNames[part] ?: axisNames[part] ?: part
            }
        val suffix = if (inverted) " (inverted)" else ""
        val prefix = if (joy.isNotEmpty() && joy != "1") "Pad $joy: " else ""
        return prefix + name + suffix
    }

    fun describeControllerBindings(value: String?): String {
        val tokens = splitTokens(value).filter(::isControllerToken)
        if (tokens.isEmpty()) return "Not bound"
        return tokens.joinToString(", ") { describeToken(it) }
    }

    // --- Capturing Android input events ------------------------------------------------------

    // Supermodel button numbers are 1-based and follow AndroidInputSystem::ButtonPressedFor.
    fun tokenForKey(keyCode: Int, def: InputDef): String? {
        val joy = "JOY${def.player}"
        if (def.kind == Kind.AXIS) return null
        val part =
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> "BUTTON1"
                KeyEvent.KEYCODE_BUTTON_B -> "BUTTON2"
                KeyEvent.KEYCODE_BUTTON_X -> "BUTTON3"
                KeyEvent.KEYCODE_BUTTON_Y -> "BUTTON4"
                KeyEvent.KEYCODE_BUTTON_L1 -> "BUTTON5"
                KeyEvent.KEYCODE_BUTTON_R1 -> "BUTTON6"
                KeyEvent.KEYCODE_BUTTON_SELECT -> "BUTTON7"
                KeyEvent.KEYCODE_BUTTON_START -> "BUTTON8"
                KeyEvent.KEYCODE_BUTTON_THUMBL -> "BUTTON9"
                KeyEvent.KEYCODE_BUTTON_THUMBR -> "BUTTON10"
                KeyEvent.KEYCODE_BUTTON_MODE -> "BUTTON13"
                KeyEvent.KEYCODE_BUTTON_L2 -> "ZAXIS_POS"
                KeyEvent.KEYCODE_BUTTON_R2 -> "RZAXIS_POS"
                KeyEvent.KEYCODE_DPAD_UP -> "POV1_UP"
                KeyEvent.KEYCODE_DPAD_DOWN -> "POV1_DOWN"
                KeyEvent.KEYCODE_DPAD_LEFT -> "POV1_LEFT"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "POV1_RIGHT"
                else -> null
            } ?: return null
        return "${joy}_$part"
    }

    private data class AxisInfo(val androidAxis: Int, val name: String, val isTrigger: Boolean = false)

    // Android axis -> SDL GameController axis as read by AndroidInputSystem::AxisValueFor.
    private val captureAxes =
        listOf(
            AxisInfo(MotionEvent.AXIS_X, "XAXIS"),
            AxisInfo(MotionEvent.AXIS_Y, "YAXIS"),
            AxisInfo(MotionEvent.AXIS_Z, "RXAXIS"),
            AxisInfo(MotionEvent.AXIS_RZ, "RYAXIS"),
            AxisInfo(MotionEvent.AXIS_RX, "RXAXIS"),
            AxisInfo(MotionEvent.AXIS_RY, "RYAXIS"),
            AxisInfo(MotionEvent.AXIS_LTRIGGER, "ZAXIS", isTrigger = true),
            AxisInfo(MotionEvent.AXIS_BRAKE, "ZAXIS", isTrigger = true),
            AxisInfo(MotionEvent.AXIS_RTRIGGER, "RZAXIS", isTrigger = true),
            AxisInfo(MotionEvent.AXIS_GAS, "RZAXIS", isTrigger = true),
        )

    private const val CAPTURE_THRESHOLD = 0.6f

    fun tokenForMotion(event: MotionEvent, def: InputDef): String? {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return null
        val joy = "JOY${def.player}"
        val device = event.device

        if (def.kind == Kind.BUTTON) {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            when {
                hatX <= -0.5f -> return "${joy}_POV1_LEFT"
                hatX >= 0.5f -> return "${joy}_POV1_RIGHT"
                hatY <= -0.5f -> return "${joy}_POV1_UP"
                hatY >= 0.5f -> return "${joy}_POV1_DOWN"
            }
        }

        var best: AxisInfo? = null
        var bestValue = 0f
        for (info in captureAxes) {
            if (device != null && device.getMotionRange(info.androidAxis, event.source) == null) continue
            val v = event.getAxisValue(info.androidAxis)
            if (abs(v) >= CAPTURE_THRESHOLD && abs(v) > abs(bestValue)) {
                best = info
                bestValue = v
            }
        }
        val axis = best ?: return null
        return when {
            def.kind == Kind.AXIS -> "${joy}_${axis.name}"
            axis.isTrigger -> "${joy}_${axis.name}_POS"
            bestValue > 0 -> "${joy}_${axis.name}_POS"
            else -> "${joy}_${axis.name}_NEG"
        }
    }
}
