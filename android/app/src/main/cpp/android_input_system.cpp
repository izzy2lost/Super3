#include "android_input_system.h"

#include <algorithm>
#include <cmath>
#include <cstring>

AndroidInputSystem::AndroidInputSystem()
  : CInputSystem("android-sdl"),
    m_keys(SDL_NUM_SCANCODES, 0)
{
  std::memset(&m_mouseDetails, 0, sizeof(m_mouseDetails));
  std::strncpy(m_mouseDetails.name, "Touchscreen", MAX_NAME_LENGTH);
  m_mouseDetails.name[MAX_NAME_LENGTH] = '\0';
  m_mouseDetails.isAbsolute = true;

  std::memset(&m_virtualJoyDetails, 0, sizeof(m_virtualJoyDetails));
  std::strncpy(m_virtualJoyDetails.name, "Touch Controls", MAX_NAME_LENGTH);
  m_virtualJoyDetails.name[MAX_NAME_LENGTH] = '\0';
  m_virtualJoyDetails.numAxes = 6;
  m_virtualJoyDetails.numPOVs = 0;
  m_virtualJoyDetails.numButtons = 0;
  m_virtualJoyDetails.hasFFeedback = false;
  for (int a = 0; a < NUM_JOY_AXES; a++)
  {
    m_virtualJoyDetails.hasAxis[a] = false;
    m_virtualJoyDetails.axisHasFF[a] = false;
    std::strncpy(m_virtualJoyDetails.axisName[a], GetDefaultAxisName(a), MAX_NAME_LENGTH);
    m_virtualJoyDetails.axisName[a][MAX_NAME_LENGTH] = '\0';
  }
  m_virtualJoyDetails.hasAxis[AXIS_X] = true;
}

AndroidInputSystem::~AndroidInputSystem()
{
  CloseControllers();
}

void AndroidInputSystem::SetGunTouchEnabled(bool enabled)
{
  if (m_gunTouchEnabled == enabled)
    return;
  m_gunTouchEnabled = enabled;
  m_gunFingerActive = false;
  m_gunFinger = 0;
  m_mousePadActive = false;
  m_mousePadFinger = 0;
  m_mousePadLastX = 0.0f;
  m_mousePadLastY = 0.0f;
  m_mouseX = 0;
  m_mouseY = 0;
  m_mouseWheelDir = 0;
  std::memset(m_mouseButtons, 0, sizeof(m_mouseButtons));
  std::memset(m_mouseButtonPulseUntilMs, 0, sizeof(m_mouseButtonPulseUntilMs));
}

void AndroidInputSystem::SetGunStickAimEnabled(bool enabled)
{
  if (m_gunStickAimEnabled == enabled)
    return;
  m_gunStickAimEnabled = enabled;
  m_lastGunAimTickMs = 0;
  if (m_gunStickAimEnabled)
    CenterMouse();
}

void AndroidInputSystem::SetVirtualWheelEnabled(bool enabled)
{
  if (m_virtualWheelEnabled == enabled)
    return;
  m_virtualWheelEnabled = enabled;
  m_wheelFingerActive = false;
  m_wheelFinger = 0;
  m_virtualJoyX.store(0, std::memory_order_relaxed);
  m_virtualJoyY = 0;
  UpdateVirtualJoystickDescriptor();
}

void AndroidInputSystem::SetVirtualShifterMode(bool shift4, bool shiftUpDown)
{
  m_virtualShifterShift4 = shift4;
  m_virtualShifterUpDown = shiftUpDown;
  m_lastVirtualGear = -1;
}

void AndroidInputSystem::SetVirtualAnalogGunEnabled(bool enabled)
{
  if (m_virtualAnalogGunEnabled == enabled)
    return;
  m_virtualAnalogGunEnabled = enabled;
  UpdateVirtualJoystickDescriptor();
  m_virtualJoyX.store(0, std::memory_order_relaxed);
  m_virtualJoyY = 0;
}

void AndroidInputSystem::SetVirtualAnalogJoystickEnabled(bool enabled)
{
  if (m_virtualAnalogJoystickEnabled == enabled)
    return;
  m_virtualAnalogJoystickEnabled = enabled;
  UpdateVirtualJoystickDescriptor();
  m_virtualJoyX.store(0, std::memory_order_relaxed);
  m_virtualJoyY = 0;
}

bool AndroidInputSystem::UseVirtualWheel() const
{
  return m_virtualWheelEnabled && m_controllers.empty();
}

bool AndroidInputSystem::UseVirtualJoystick() const
{
  return m_controllers.empty() && (m_virtualWheelEnabled || m_virtualAnalogGunEnabled || m_virtualAnalogJoystickEnabled);
}

void AndroidInputSystem::SetVirtualSteerFromEncoded(float encodedX)
{
  // encodedX comes from Java as [(steer+1)/2], where steer is in [-1,1].
  SetVirtualSteerFromSteer((encodedX - 0.5f) * 2.0f);
}

void AndroidInputSystem::UpdateVirtualJoystickDescriptor()
{
  const bool wheel = m_virtualWheelEnabled;
  const bool gun = m_virtualAnalogGunEnabled;
  const bool stick = m_virtualAnalogJoystickEnabled;
  const int enabledCount = (wheel ? 1 : 0) + (gun ? 1 : 0) + (stick ? 1 : 0);

  m_virtualJoyDetails.hasAxis[AXIS_Y] = gun || stick;

  const char* name = "Touch Controls";
  if (enabledCount <= 1)
  {
    if (wheel) name = "Touch Wheel";
    else if (gun) name = "Touch Gun";
    else if (stick) name = "Touch Stick";
  }

  std::strncpy(m_virtualJoyDetails.name, name, MAX_NAME_LENGTH);
  m_virtualJoyDetails.name[MAX_NAME_LENGTH] = '\0';
}

void AndroidInputSystem::SetVirtualSteerFromSteer(float steer)
{
  steer = std::clamp(steer, -1.0f, 1.0f);
  constexpr float deadzone = 0.08f;
  if (std::abs(steer) < deadzone)
  {
    m_virtualJoyX.store(0, std::memory_order_relaxed);
    return;
  }

  const float scaled = (std::abs(steer) - deadzone) / (1.0f - deadzone);
  const float signedScaled = (steer < 0.0f) ? -scaled : scaled;
  m_virtualJoyX.store(
    (int)std::lround(std::clamp(signedScaled, -1.0f, 1.0f) * 32767.0f),
    std::memory_order_relaxed
  );
}

void AndroidInputSystem::SetGyroSteer(float steer)
{
  SetVirtualSteerFromSteer(steer);
}

void AndroidInputSystem::SetVirtualJoyFromNormalized(float x, float y)
{
  const float sx = std::clamp((x - 0.5f) * 2.0f, -1.0f, 1.0f);
  const float sy = std::clamp((y - 0.5f) * 2.0f, -1.0f, 1.0f);
  m_virtualJoyX.store((int)std::lround(sx * 32767.0f), std::memory_order_relaxed);
  m_virtualJoyY = (int)std::lround(sy * 32767.0f);
}

void AndroidInputSystem::SetVirtualStickFromNormalized(float x, float y)
{
  float sx = std::clamp((x - 0.5f) * 2.0f, -1.0f, 1.0f);
  float sy = std::clamp((y - 0.5f) * 2.0f, -1.0f, 1.0f);

  // Apply a circular deadzone + a mild curve so the center is less twitchy.
  const float mag = std::sqrt(sx * sx + sy * sy);
  constexpr float deadzone = 0.18f;
  if (mag < deadzone)
  {
    m_virtualJoyX.store(0, std::memory_order_relaxed);
    m_virtualJoyY = 0;
    return;
  }

  float scaled = (mag - deadzone) / (1.0f - deadzone);
  scaled = std::clamp(scaled, 0.0f, 1.0f);
  scaled *= scaled;

  const float invMag = 1.0f / mag;
  sx = sx * invMag * scaled;
  sy = sy * invMag * scaled;

  m_virtualJoyX.store((int)std::lround(sx * 32767.0f), std::memory_order_relaxed);
  m_virtualJoyY = (int)std::lround(sy * 32767.0f);
}

void AndroidInputSystem::ApplyConfig(const Util::Config::Node& config)
{
  auto get = [&](const char* key, const char* fallback) -> std::string {
    return config[key].ValueAsDefault<std::string>(fallback);
  };

  {
    const std::string newPath = config["SDLGameControllerDB"].ValueAsDefault<std::string>("");
    if (newPath != m_gameControllerMappingsPath)
    {
      m_gameControllerMappingsPath = newPath;
      m_gameControllerMappingsLoaded = false;
    }
  }

  m_gunAutoTrigger = config["InputAutoTrigger"].ValueAsDefault<unsigned>(0) != 0;
  m_controllerShortcuts = config["AndroidControllerShortcuts"].ValueAsDefault<unsigned>(1) != 0;

  auto keySc = [&](const std::string& mapping, SDL_Scancode fallback) -> SDL_Scancode {
    if (mapping.empty()) return fallback;
    SDL_Scancode sc = ParseFirstKeyboardScancode(mapping, *this);
    return (sc == SDL_SCANCODE_UNKNOWN) ? fallback : sc;
  };

  m_touchCoin.a = keySc(get("InputCoin1", "KEY_5"), SDL_SCANCODE_5);
  m_touchStart.a = keySc(get("InputStart1", "KEY_1"), SDL_SCANCODE_1);
  m_touchService.a = keySc(get("InputServiceA", "KEY_F1"), SDL_SCANCODE_F1);
  m_touchTest.a = keySc(get("InputTestA", "KEY_F2"), SDL_SCANCODE_F2);

  // For left/right, we press both joy and steering if they differ, to keep menus and driving working.
  const SDL_Scancode joyLeft = keySc(get("InputJoyLeft", "KEY_LEFT"), SDL_SCANCODE_LEFT);
  const SDL_Scancode joyRight = keySc(get("InputJoyRight", "KEY_RIGHT"), SDL_SCANCODE_RIGHT);
  const SDL_Scancode steerLeft = keySc(get("InputSteeringLeft", "KEY_LEFT"), SDL_SCANCODE_LEFT);
  const SDL_Scancode steerRight = keySc(get("InputSteeringRight", "KEY_RIGHT"), SDL_SCANCODE_RIGHT);

  m_touchJoyUp.a = keySc(get("InputJoyUp", "KEY_UP"), SDL_SCANCODE_UP);
  m_touchJoyDown.a = keySc(get("InputJoyDown", "KEY_DOWN"), SDL_SCANCODE_DOWN);

  m_touchJoyLeft = {joyLeft, (joyLeft != steerLeft) ? steerLeft : SDL_SCANCODE_UNKNOWN};
  m_touchJoyRight = {joyRight, (joyRight != steerRight) ? steerRight : SDL_SCANCODE_UNKNOWN};
  m_touchSteerLeft = {steerLeft, (steerLeft != joyLeft) ? joyLeft : SDL_SCANCODE_UNKNOWN};
  m_touchSteerRight = {steerRight, (steerRight != joyRight) ? joyRight : SDL_SCANCODE_UNKNOWN};

  m_touchThrottle.a = keySc(get("InputAccelerator", "KEY_W"), SDL_SCANCODE_W);
  m_touchBrake.a = keySc(get("InputBrake", "KEY_X"), SDL_SCANCODE_X);

  m_touchShiftUp.a = keySc(get("InputGearShiftUp", "KEY_I"), SDL_SCANCODE_I);
  m_touchShiftDown.a = keySc(get("InputGearShiftDown", "KEY_K"), SDL_SCANCODE_K);
  m_touchShift1.a = keySc(get("InputGearShift1", "KEY_7"), SDL_SCANCODE_7);
  m_touchShift2.a = keySc(get("InputGearShift2", "KEY_8"), SDL_SCANCODE_8);
  m_touchShift3.a = keySc(get("InputGearShift3", "KEY_9"), SDL_SCANCODE_9);
  m_touchShift4.a = keySc(get("InputGearShift4", "KEY_0"), SDL_SCANCODE_0);
  m_touchShiftN.a = keySc(get("InputGearShiftN", "KEY_6"), SDL_SCANCODE_6);

  m_touchViewChange.a = keySc(get("InputViewChange", "KEY_A"), SDL_SCANCODE_A);
  m_touchVr1.a = keySc(get("InputVR1", "KEY_A"), SDL_SCANCODE_A);
  m_touchVr2.a = keySc(get("InputVR2", "KEY_S"), SDL_SCANCODE_S);
  m_touchVr3.a = keySc(get("InputVR3", "KEY_D"), SDL_SCANCODE_D);
  m_touchVr4.a = keySc(get("InputVR4", "KEY_F"), SDL_SCANCODE_F);

  m_touchPunch.a = keySc(get("InputPunch", "KEY_A"), SDL_SCANCODE_A);
  m_touchKick.a = keySc(get("InputKick", "KEY_S"), SDL_SCANCODE_S);
  m_touchGuard.a = keySc(get("InputGuard", "KEY_D"), SDL_SCANCODE_D);
  m_touchEscape.a = keySc(get("InputEscape", "KEY_F"), SDL_SCANCODE_F);

  m_touchSpikeShift.a = keySc(get("InputShift", "KEY_A"), SDL_SCANCODE_A);
  m_touchSpikeBeat.a = keySc(get("InputBeat", "KEY_S"), SDL_SCANCODE_S);
  m_touchSpikeCharge.a = keySc(get("InputCharge", "KEY_D"), SDL_SCANCODE_D);
  m_touchSpikeJump.a = keySc(get("InputJump", "KEY_F"), SDL_SCANCODE_F);

  m_touchTwinJump.a = keySc(get("InputTwinJoyJump", "KEY_E"), SDL_SCANCODE_E);
  {
    const SDL_Scancode turbo1 = keySc(get("InputTwinJoyTurbo1", "KEY_Z"), SDL_SCANCODE_Z);
    m_touchTwinBoost = {turbo1, SDL_SCANCODE_UNKNOWN};
  }

  m_touchFishingCast.a = keySc(get("InputFishingCast", "KEY_Z"), SDL_SCANCODE_Z);
  m_touchFishingSelect.a = keySc(get("InputFishingSelect", "KEY_X"), SDL_SCANCODE_X);
  m_touchFishingReel.a = keySc(get("InputFishingReel", "KEY_SPACE"), SDL_SCANCODE_SPACE);
  m_touchFishingTension.a = keySc(get("InputFishingTension", "KEY_T"), SDL_SCANCODE_T);

  m_touchMagPedal1.a = keySc(get("InputMagicalPedal1", "KEY_A"), SDL_SCANCODE_A);
  m_touchMagPedal2.a = keySc(get("InputMagicalPedal2", "KEY_S"), SDL_SCANCODE_S);

  m_touchSkiPollLeft.a = keySc(get("InputSkiPollLeft", "KEY_A"), SDL_SCANCODE_A);
  m_touchSkiPollRight.a = keySc(get("InputSkiPollRight", "KEY_S"), SDL_SCANCODE_S);
  m_touchSkiSelect1.a = keySc(get("InputSkiSelect1", "KEY_Q"), SDL_SCANCODE_Q);
  m_touchSkiSelect2.a = keySc(get("InputSkiSelect2", "KEY_W"), SDL_SCANCODE_W);

  m_touchCrosshairs.a = keySc(get("InputUISelectCrosshairs", "KEY_F8"), SDL_SCANCODE_F8);
}

void AndroidInputSystem::EnsureGameControllerMappingsLoaded()
{
  if (m_gameControllerMappingsLoaded)
    return;

  std::string mappingsPath = m_gameControllerMappingsPath;
  if (mappingsPath.empty())
    mappingsPath = "Config/gamecontrollerdb.txt";

  SDL_RWops* rw = SDL_RWFromFile(mappingsPath.c_str(), "rb");
  if (rw != nullptr)
  {
    const int added = SDL_GameControllerAddMappingsFromRW(rw, 1);
    if (added > 0)
      SDL_Log("Loaded %d SDL game controller mappings from %s", added, mappingsPath.c_str());
    else if (added < 0)
      SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Failed to load SDL game controller mappings from %s (%s)", mappingsPath.c_str(), SDL_GetError());
  }

  m_gameControllerMappingsLoaded = true;
}

bool AndroidInputSystem::InitializeSystem()
{
  std::fill(m_keys.begin(), m_keys.end(), 0);
  m_fingerHeldDir.clear();
  m_fingerHeldKey.clear();
  m_pulseUntilMs.clear();
  m_mouseX = 0;
  m_mouseY = 0;
  m_mouseWheelDir = 0;
  std::memset(m_mouseButtons, 0, sizeof(m_mouseButtons));
  std::memset(m_mouseButtonPulseUntilMs, 0, sizeof(m_mouseButtonPulseUntilMs));
  m_gunFingerActive = false;
  m_gunFinger = 0;
  m_mousePadActive = false;
  m_mousePadFinger = 0;
  m_mousePadLastX = 0.0f;
  m_mousePadLastY = 0.0f;
  m_suppressGunTriggerUntilMs = 0;
  m_pendingReloadTriggerAtMs = 0;
  m_lastGunAimTickMs = 0;
  m_wheelFingerActive = false;
  m_wheelFinger = 0;
  m_virtualJoyX.store(0, std::memory_order_relaxed);
  m_virtualJoyY = 0;
  m_lastVirtualGear = -1;

  EnsureGameControllerMappingsLoaded();
  SDL_GameControllerEventState(SDL_ENABLE);
  RefreshControllers();
  return true;
}

void AndroidInputSystem::SetKey(SDL_Scancode sc, bool down)
{
  if (sc <= SDL_SCANCODE_UNKNOWN || sc >= SDL_NUM_SCANCODES)
    return;
  m_keys[static_cast<size_t>(sc)] = down ? 1 : 0;
}

void AndroidInputSystem::PulseKey(SDL_Scancode sc, uint32_t durationMs)
{
  if (sc <= SDL_SCANCODE_UNKNOWN || sc >= SDL_NUM_SCANCODES)
    return;
  SetKey(sc, true);
  m_pulseUntilMs[sc] = SDL_GetTicks() + durationMs;
}

void AndroidInputSystem::SetKeys(const DualScancode& sc, bool down)
{
  if (sc.a != SDL_SCANCODE_UNKNOWN) SetKey(sc.a, down);
  if (sc.b != SDL_SCANCODE_UNKNOWN) SetKey(sc.b, down);
}

void AndroidInputSystem::PulseKeys(const DualScancode& sc, uint32_t durationMs)
{
  if (sc.a != SDL_SCANCODE_UNKNOWN) PulseKey(sc.a, durationMs);
  if (sc.b != SDL_SCANCODE_UNKNOWN) PulseKey(sc.b, durationMs);
}

bool AndroidInputSystem::Poll()
{
  SDL_GameControllerUpdate();
  SDL_JoystickUpdate();

  const uint32_t now = SDL_GetTicks();
  UpdateGunStickAim(now);
  if (m_pendingReloadTriggerAtMs != 0 && now >= m_pendingReloadTriggerAtMs)
  {
    // Manual reload helper when the core's InputAutoTrigger is disabled:
    // fire the trigger shortly after offscreen is pressed (offscreen->trigger order).
    m_suppressGunTriggerUntilMs = 0;
    PulseMouseButton(0, 80);
    m_pendingReloadTriggerAtMs = 0;
  }
  for (auto it = m_pulseUntilMs.begin(); it != m_pulseUntilMs.end();)
  {
    if (now >= it->second)
    {
      SetKey(static_cast<SDL_Scancode>(it->first), false);
      it = m_pulseUntilMs.erase(it);
    }
    else
    {
      ++it;
    }
  }

  for (int i = 0; i < kMouseButtons; i++)
  {
    if (m_mouseButtonPulseUntilMs[i] != 0 && now >= m_mouseButtonPulseUntilMs[i])
    {
      m_mouseButtonPulseUntilMs[i] = 0;
      m_mouseButtons[i] = 0;
    }
  }
  return true;
}

void AndroidInputSystem::HandleEvent(const SDL_Event& ev)
{
  switch (ev.type)
  {
    case SDL_CONTROLLERDEVICEADDED:
    case SDL_CONTROLLERDEVICEREMOVED:
    case SDL_CONTROLLERDEVICEREMAPPED:
    case SDL_JOYDEVICEADDED:
    case SDL_JOYDEVICEREMOVED:
      RefreshControllers();
      break;
    case SDL_KEYDOWN:
      HandleKeyEvent(ev.key, true);
      break;
    case SDL_KEYUP:
      HandleKeyEvent(ev.key, false);
      break;
    case SDL_CONTROLLERBUTTONDOWN:
      HandleControllerButtonEvent(ev.cbutton, true);
      break;
    case SDL_CONTROLLERBUTTONUP:
      HandleControllerButtonEvent(ev.cbutton, false);
      break;
    case SDL_FINGERDOWN:
      HandleTouch(ev.tfinger, true);
      break;
    case SDL_FINGERUP:
      HandleTouch(ev.tfinger, false);
      break;
    case SDL_FINGERMOTION:
      HandleTouchMotion(ev.tfinger);
      break;
    default:
      break;
  }
}

void AndroidInputSystem::HandleKeyEvent(const SDL_KeyboardEvent& key, bool down)
{
  SetKey(key.keysym.scancode, down);
}

void AndroidInputSystem::HandleControllerButtonEvent(const SDL_ControllerButtonEvent& btn, bool down)
{
  // Controller buttons can be mapped in Supermodel.ini via JOY mappings, but we also
  // synthesize a few "touch-style" keys for convenience in the UI / test menu.
  // Custom remaps (AndroidControllerShortcuts = 0) disable this so they aren't doubled up.
  if (!m_controllerShortcuts)
    return;

  DualScancode sc;
  switch (btn.button)
  {
    case SDL_CONTROLLER_BUTTON_START: sc = m_touchStart; break;
    case SDL_CONTROLLER_BUTTON_BACK: sc = m_touchCoin; break;
    case SDL_CONTROLLER_BUTTON_GUIDE: sc = m_touchService; break;
    case SDL_CONTROLLER_BUTTON_DPAD_UP: sc = m_touchJoyUp; break;
    case SDL_CONTROLLER_BUTTON_DPAD_DOWN: sc = m_touchJoyDown; break;
    case SDL_CONTROLLER_BUTTON_DPAD_LEFT: sc = m_touchJoyLeft; break;
    case SDL_CONTROLLER_BUTTON_DPAD_RIGHT: sc = m_touchJoyRight; break;
    default: break;
  }

  if (sc.a == SDL_SCANCODE_UNKNOWN && sc.b == SDL_SCANCODE_UNKNOWN)
    return;

  // For coin/start/test, prefer a pulse to avoid repeating while held.
  if (down && (sc.a == m_touchStart.a || sc.a == m_touchCoin.a || sc.a == m_touchService.a || sc.a == m_touchTest.a))
    PulseKeys(sc, 120);
  else
    SetKeys(sc, down);
}

void AndroidInputSystem::HandleTouch(const SDL_TouchFingerEvent& tf, bool down)
{
  const float x = tf.x;
  const float y = tf.y;

  // Fighting game action buttons (fingerId encoded from Java).
  switch (tf.fingerId)
  {
    // Overlay buttons (synthetic finger IDs from Java). Prefer these over coordinate-based zones
    // so the Start button can be held for modes that require it (e.g. time attack).
    case 1101: // Coin
      if (down) PulseKeys(m_touchCoin, 120);
      return;
    case 1102: // Start
      SetKeys(m_touchStart, down);
      return;
    case 1105: // Service
      if (down) PulseKeys(m_touchService, 120);
      return;
    case 1106: // Test
      if (down) PulseKeys(m_touchTest, 120);
      return;

    case 1152: SetKeys(m_touchViewChange, down); return;
    case 1153: SetKeys(m_touchVr1, down); return;
    case 1154: SetKeys(m_touchVr2, down); return;
    case 1155: SetKeys(m_touchVr3, down); return;
    case 1156: SetKeys(m_touchVr4, down); return;
    case 1110: SetKeys(m_touchPunch, down); return;
    case 1111: SetKeys(m_touchKick, down); return;
    case 1112: SetKeys(m_touchGuard, down); return;
    case 1113: SetKeys(m_touchEscape, down); return;
    case 1115: SetKeys(m_touchSpikeShift, down); return;
    case 1116: SetKeys(m_touchSpikeBeat, down); return;
    case 1117: SetKeys(m_touchSpikeCharge, down); return;
    case 1118: SetKeys(m_touchSpikeJump, down); return;
    case 1150: SetKeys(m_touchTwinJump, down); return;
    case 1151: SetKeys(m_touchTwinBoost, down); return;
    case 1120: SetKeys(m_touchFishingCast, down); return;
    case 1121: SetKeys(m_touchFishingSelect, down); return;
    case 1122: SetKeys(m_touchFishingReel, down); return;
    case 1123: SetKeys(m_touchFishingTension, down); return;
    case 1130: SetKeys(m_touchMagPedal1, down); return;
    case 1131: SetKeys(m_touchMagPedal2, down); return;
    case 1140: SetKeys(m_touchSkiPollLeft, down); return;
    case 1141: SetKeys(m_touchSkiPollRight, down); return;
    case 1142: SetKeys(m_touchSkiSelect1, down); return;
    case 1143: SetKeys(m_touchSkiSelect2, down); return;
    case 1119: // Crosshairs toggle
      if (down) PulseKeys(m_touchCrosshairs, 120);
      return;
    default: break;
  }

  // Virtual mouse pad (relative mouse movement).
  if (tf.fingerId == 1125)
  {
    if (down)
    {
      if (!m_mousePadActive)
      {
        m_mousePadActive = true;
        m_mousePadFinger = tf.fingerId;
        m_mousePadLastX = tf.x;
        m_mousePadLastY = tf.y;
      }
    }
    else
    {
      if (m_mousePadActive && tf.fingerId == m_mousePadFinger)
      {
        m_mousePadActive = false;
        m_mousePadFinger = 0;
      }
    }
    return;
  }

  // Virtual fighting stick: 8-way directional based on encoded x/y in [0..1], keyed by a fixed fingerId.
  if (tf.fingerId == 1114)
  {
    if (m_virtualAnalogJoystickEnabled || m_virtualAnalogGunEnabled)
    {
      if (down)
        SetVirtualStickFromNormalized(x, y);
      else
        SetVirtualStickFromNormalized(0.5f, 0.5f);
      return;
    }

    auto releaseHeld = [&]() {
      auto it = m_fingerHeldDir.find(tf.fingerId);
      if (it != m_fingerHeldDir.end())
      {
        SetKeys(it->second.primary, false);
        SetKeys(it->second.secondary, false);
        m_fingerHeldDir.erase(it);
      }
    };

    if (!down)
    {
      releaseHeld();
      return;
    }

    const float sx = std::clamp((x - 0.5f) * 2.0f, -1.0f, 1.0f);
    const float sy = std::clamp((y - 0.5f) * 2.0f, -1.0f, 1.0f);
    constexpr float deadzone = 0.25f;
    if (std::abs(sx) < deadzone && std::abs(sy) < deadzone)
    {
      releaseHeld();
      return;
    }

    constexpr float kPi = 3.14159265358979323846f;
    float angle = std::atan2(-sy, sx); // y is down in normalized coords; invert for math-y up.
    if (angle < 0.0f)
      angle += 2.0f * kPi;

    const int oct = (int)std::floor((angle + (kPi / 8.0f)) / (kPi / 4.0f)) & 7;
    DualScancode h{};
    DualScancode v{};
    switch (oct)
    {
      case 0: h = m_touchJoyRight; break;
      case 1: h = m_touchJoyRight; v = m_touchJoyUp; break;
      case 2: v = m_touchJoyUp; break;
      case 3: h = m_touchJoyLeft; v = m_touchJoyUp; break;
      case 4: h = m_touchJoyLeft; break;
      case 5: h = m_touchJoyLeft; v = m_touchJoyDown; break;
      case 6: v = m_touchJoyDown; break;
      case 7: h = m_touchJoyRight; v = m_touchJoyDown; break;
      default: break;
    }

    releaseHeld();
    HeldDirKeys held{};
    if (h.a != SDL_SCANCODE_UNKNOWN || h.b != SDL_SCANCODE_UNKNOWN)
      held.primary = h;
    if (v.a != SDL_SCANCODE_UNKNOWN || v.b != SDL_SCANCODE_UNKNOWN)
    {
      if (held.primary.a == SDL_SCANCODE_UNKNOWN && held.primary.b == SDL_SCANCODE_UNKNOWN)
        held.primary = v;
      else
        held.secondary = v;
    }
    if (held.primary.a != SDL_SCANCODE_UNKNOWN || held.primary.b != SDL_SCANCODE_UNKNOWN)
      SetKeys(held.primary, true);
    if (held.secondary.a != SDL_SCANCODE_UNKNOWN || held.secondary.b != SDL_SCANCODE_UNKNOWN)
      SetKeys(held.secondary, true);
    m_fingerHeldDir[tf.fingerId] = held;
    return;
  }

  // Virtual manual shifter (racing games): encoded in tf.x/tf.y from Java and keyed by a fixed fingerId.
  if ((m_virtualShifterShift4 || m_virtualShifterUpDown) && tf.fingerId == 1108)
  {
    if (!down)
      return;

    if (m_virtualShifterShift4)
    {
      const float dx = x - 0.5f;
      const float dy = y - 0.5f;
      const bool inNeutral = (std::abs(dx) < 0.18f && std::abs(dy) < 0.18f);

      int gear = m_lastVirtualGear;
      if (inNeutral)
      {
        gear = 0;
      }
      else
      {
        const bool left = dx < 0.0f;
        const bool upper = dy < 0.0f;
        if (left && upper) gear = 1;
        else if (left && !upper) gear = 2;
        else if (!left && upper) gear = 3;
        else gear = 4;
      }

      if (gear != m_lastVirtualGear)
      {
        switch (gear)
        {
          case 0: PulseKeys(m_touchShiftN, 120); break;
          case 1: PulseKeys(m_touchShift1, 120); break;
          case 2: PulseKeys(m_touchShift2, 120); break;
          case 3: PulseKeys(m_touchShift3, 120); break;
          case 4: PulseKeys(m_touchShift4, 120); break;
          default: break;
        }
        m_lastVirtualGear = gear;
      }
      return;
    }

    // Up/down shifter: tap upper/lower half.
    if (m_virtualShifterUpDown)
    {
      if (y < 0.5f) PulseKeys(m_touchShiftUp, 120);
      else PulseKeys(m_touchShiftDown, 120);
      return;
    }
  }

  // Virtual steering wheel (racing games): encoded in tf.x from Java and keyed by a fixed fingerId.
  if (UseVirtualWheel())
  {
    constexpr SDL_FingerID kWheelFingerId = 1107;
    if (down)
    {
      if (!m_wheelFingerActive && tf.fingerId == kWheelFingerId)
      {
        m_wheelFingerActive = true;
        m_wheelFinger = tf.fingerId;
        SetVirtualSteerFromEncoded(x);
        return;
      }
    }
    else
    {
      if (m_wheelFingerActive && tf.fingerId == m_wheelFinger)
      {
        SetVirtualSteerFromEncoded(0.5f);
        m_wheelFingerActive = false;
        m_wheelFinger = 0;
        return;
      }
    }
  }

  // Tap zones (momentary):
  // - Bottom-left: Coin (KEY_5)
  // - Bottom-middle: Start (KEY_1)
  // - Top-left: Service (KEY_F1)
  // - Top-right: Test (KEY_F2)
  if (down)
  {
    if (x < 0.25f && y > 0.75f) { PulseKeys(m_touchCoin, 120); return; }
    if (x > 0.40f && x < 0.60f && y > 0.75f) { PulseKeys(m_touchStart, 120); return; }
    if (x < 0.25f && y < 0.25f) { PulseKeys(m_touchService, 120); return; }
    if (x > 0.75f && y < 0.25f) { PulseKeys(m_touchTest, 120); return; }
  }

  // Lightgun reload button: treat a dedicated synthetic fingerId as offscreen/reload,
  // independent of the aiming touch.
  if (m_gunTouchEnabled && tf.fingerId == 1109)
  {
    if (down)
    {
      // Temporarily suppress trigger so reload happens in the correct order even if the
      // user is holding the screen to aim/fire (our touch mapping holds MOUSE_LEFT_BUTTON).
      m_suppressGunTriggerUntilMs = SDL_GetTicks() + 150;

      // Keep offscreen asserted long enough that the core's poll loop won't miss it.
      PulseMouseButton(2, 140); // right = reload/offscreen

      // If the core auto-trigger is disabled, approximate the same behavior ourselves.
      if (!m_gunAutoTrigger)
        m_pendingReloadTriggerAtMs = SDL_GetTicks() + 90;
    }
    return;
  }

  // Lightgun/analog-gun games: use the touchscreen as an absolute "mouse" so
  // existing MOUSE_XAXIS/MOUSE_YAXIS + MOUSE_LEFT_BUTTON mappings work without
  // a physical mouse.
  if (m_gunTouchEnabled)
  {
    if (down)
    {
      if (!m_gunFingerActive)
      {
        m_gunFingerActive = true;
        m_gunFinger = tf.fingerId;
        SetMousePosFromNormalized(x, y);
        SetMouseButton(0, true); // left = trigger (held)
      }
    }
    else
    {
      if (m_gunFingerActive && tf.fingerId == m_gunFinger)
      {
        SetMouseButton(0, false);
        m_gunFingerActive = false;
        m_gunFinger = 0;
      }
    }
    return;
  }

  // Held throttle/brake zone (right-middle): hold to accelerate/brake.
  // - Upper half: throttle (KEY_W)
  // - Lower half: brake (KEY_S)
  const bool inPedalZone = (x > 0.55f && y >= 0.25f && y <= 0.90f);
  if (inPedalZone)
  {
    if (!down)
    {
      auto it = m_fingerHeldKey.find(tf.fingerId);
      if (it != m_fingerHeldKey.end())
      {
        SetKeys(it->second, false);
        m_fingerHeldKey.erase(it);
      }
      return;
    }

    DualScancode pedal = (y < 0.575f) ? m_touchThrottle : m_touchBrake;
    m_fingerHeldKey[tf.fingerId] = pedal;
    SetKeys(pedal, true);
    return;
  }

  // Held D-pad zone (left-middle): press one of the arrow keys based on direction.
  const bool inDpadZone = (x < 0.45f && y >= 0.35f && y <= 0.75f);
  if (!inDpadZone)
    return;

  if (!down)
  {
    auto it = m_fingerHeldDir.find(tf.fingerId);
    if (it != m_fingerHeldDir.end())
    {
      SetKeys(it->second.primary, false);
      SetKeys(it->second.secondary, false);
      m_fingerHeldDir.erase(it);
    }
    return;
  }

  // Determine direction relative to center of the d-pad zone.
  const float cx = 0.225f;
  const float cy = 0.55f;
  const float dx = x - cx;
  const float dy = y - cy;

  DualScancode dir;
  if (std::abs(dx) > std::abs(dy))
    dir = (dx < 0.0f) ? m_touchJoyLeft : m_touchJoyRight;
  else
    dir = (dy < 0.0f) ? m_touchJoyUp : m_touchJoyDown;

  if (dir.a != SDL_SCANCODE_UNKNOWN || dir.b != SDL_SCANCODE_UNKNOWN)
  {
    HeldDirKeys held{};
    held.primary = dir;
    m_fingerHeldDir[tf.fingerId] = held;
    SetKeys(held.primary, true);
  }
}

void AndroidInputSystem::HandleTouchMotion(const SDL_TouchFingerEvent& tf)
{
  if (tf.fingerId == 1114)
  {
    HandleTouch(tf, true);
    return;
  }

  if (m_mousePadActive && tf.fingerId == m_mousePadFinger)
  {
    const float dx = tf.x - m_mousePadLastX;
    const float dy = tf.y - m_mousePadLastY;
    m_mousePadLastX = tf.x;
    m_mousePadLastY = tf.y;

    if (dx == 0.0f && dy == 0.0f)
      return;

    const int w = (m_dispW > 0) ? (int)m_dispW : 496;
    const int h = (m_dispH > 0) ? (int)m_dispH : 384;
    const int x0 = (int)m_dispX;
    const int y0 = (int)m_dispY;
    constexpr float sensitivity = 1.0f;
    m_mouseX += (int)std::lround(dx * (float)w * sensitivity);
    m_mouseY += (int)std::lround(dy * (float)h * sensitivity);
    const int maxX = x0 + w - 1;
    const int maxY = y0 + h - 1;
    m_mouseX = std::clamp(m_mouseX, x0, maxX);
    m_mouseY = std::clamp(m_mouseY, y0, maxY);
    return;
  }

  if (m_virtualShifterShift4 && tf.fingerId == 1108)
  {
    // Avoid accidental neutral when passing through center during motion.
    const float dx = tf.x - 0.5f;
    const float dy = tf.y - 0.5f;
    if (std::abs(dx) < 0.18f && std::abs(dy) < 0.18f)
      return;

    const bool left = dx < 0.0f;
    const bool upper = dy < 0.0f;
    int gear;
    if (left && upper) gear = 1;
    else if (left && !upper) gear = 2;
    else if (!left && upper) gear = 3;
    else gear = 4;

    if (gear != m_lastVirtualGear)
    {
      switch (gear)
      {
        case 1: PulseKeys(m_touchShift1, 120); break;
        case 2: PulseKeys(m_touchShift2, 120); break;
        case 3: PulseKeys(m_touchShift3, 120); break;
        case 4: PulseKeys(m_touchShift4, 120); break;
        default: break;
      }
      m_lastVirtualGear = gear;
    }
    return;
  }

  if (UseVirtualWheel())
  {
    if (m_wheelFingerActive && tf.fingerId == m_wheelFinger)
    {
      SetVirtualSteerFromEncoded(tf.x);
      return;
    }
  }

  if (m_gunTouchEnabled)
  {
    if (m_gunFingerActive && tf.fingerId == m_gunFinger)
    {
      SetMousePosFromNormalized(tf.x, tf.y);
    }
    return;
  }

  // Update held d-pad direction.
  auto it = m_fingerHeldDir.find(tf.fingerId);
  if (it == m_fingerHeldDir.end())
  {
    // Update held pedal if this finger is a pedal touch.
    auto itKey = m_fingerHeldKey.find(tf.fingerId);
    if (itKey == m_fingerHeldKey.end())
      return;

    // Release previous pedal and re-evaluate based on new Y position.
    SetKeys(itKey->second, false);
    m_fingerHeldKey.erase(itKey);
    HandleTouch(tf, true);
    return;
  }

  // Release previous direction and compute a new one.
  SetKeys(it->second.primary, false);
  SetKeys(it->second.secondary, false);
  m_fingerHeldDir.erase(it);
  HandleTouch(tf, true);
}

int AndroidInputSystem::GetNumMice()
{
  return m_gunTouchEnabled ? 1 : 0;
}

const MouseDetails* AndroidInputSystem::GetMouseDetails(int mseNum)
{
  if (!m_gunTouchEnabled)
    return nullptr;
  if (mseNum == ANY_MOUSE || mseNum == 0)
    return &m_mouseDetails;
  return nullptr;
}

int AndroidInputSystem::GetMouseAxisValue(int mseNum, int axisNum)
{
  if (!m_gunTouchEnabled)
    return 0;
  if (mseNum != ANY_MOUSE && mseNum != 0)
    return 0;

  // For analog-gun games, prefer the virtual joystick (JOY1_XAXIS/JOY1_YAXIS).
  // Keep the virtual mouse centered unless the user is actively aiming via touch.
  // This lets:
  // - touch aim drive MOUSE_* mappings while held, and
  // - physical controller aim drive JOY* mappings when not touching.
  if (m_virtualAnalogGunEnabled && (axisNum == AXIS_X || axisNum == AXIS_Y) && !m_gunFingerActive && !m_gunStickAimEnabled)
  {
    const unsigned extent = (axisNum == AXIS_X) ? m_dispW : m_dispH;
    const unsigned origin = (axisNum == AXIS_X) ? m_dispX : m_dispY;
    return (int)(origin + extent / 2);
  }

  switch (axisNum)
  {
    case AXIS_X: return m_mouseX;
    case AXIS_Y: return m_mouseY;
    default: return 0;
  }
}

int AndroidInputSystem::GetMouseWheelDir(int mseNum)
{
  if (!m_gunTouchEnabled)
    return 0;
  if (mseNum != ANY_MOUSE && mseNum != 0)
    return 0;
  return m_mouseWheelDir;
}

bool AndroidInputSystem::IsMouseButPressed(int mseNum, int butNum)
{
  if (!m_gunTouchEnabled)
    return false;
  if (mseNum != ANY_MOUSE && mseNum != 0)
    return false;
  if (butNum < 0 || butNum >= kMouseButtons)
    return false;
  if (butNum == 0 && m_suppressGunTriggerUntilMs != 0)
  {
    const uint32_t now = SDL_GetTicks();
    if (now < m_suppressGunTriggerUntilMs)
      return false;
    m_suppressGunTriggerUntilMs = 0;
  }
  return m_mouseButtons[butNum] != 0;
}

void AndroidInputSystem::SetMouseButton(int butNum, bool down)
{
  if (butNum < 0 || butNum >= kMouseButtons)
    return;
  m_mouseButtons[butNum] = down ? 1 : 0;
  if (!down)
    m_mouseButtonPulseUntilMs[butNum] = 0;
}

void AndroidInputSystem::PulseMouseButton(int butNum, uint32_t durationMs)
{
  if (butNum < 0 || butNum >= kMouseButtons)
    return;
  m_mouseButtons[butNum] = 1;
  m_mouseButtonPulseUntilMs[butNum] = SDL_GetTicks() + durationMs;
}

void AndroidInputSystem::SetMousePosFromNormalized(float x, float y)
{
  // The emulator polls inputs with a fixed display geometry (currently 496x384).
  // Use the same coordinate system so the core's mouse/lightgun normalization works.
  constexpr int w = 496;
  constexpr int h = 384;
  const int px = (int)std::lround(std::clamp(x, 0.0f, 1.0f) * (float)(w - 1));
  const int py = (int)std::lround(std::clamp(y, 0.0f, 1.0f) * (float)(h - 1));
  m_mouseX = std::clamp(px, 0, w - 1);
  m_mouseY = std::clamp(py, 0, h - 1);
}

void AndroidInputSystem::CenterMouse()
{
  const int w = (m_dispW > 0) ? (int)m_dispW : 496;
  const int h = (m_dispH > 0) ? (int)m_dispH : 384;
  const int x0 = (int)m_dispX;
  const int y0 = (int)m_dispY;
  m_mouseX = x0 + w / 2;
  m_mouseY = y0 + h / 2;
}

void AndroidInputSystem::UpdateGunStickAim(uint32_t nowMs)
{
  if (!m_gunStickAimEnabled || m_controllers.empty() || m_gunFingerActive || m_mousePadActive)
  {
    m_lastGunAimTickMs = nowMs;
    return;
  }

  float dt = 0.0f;
  if (m_lastGunAimTickMs != 0)
  {
    const uint32_t delta = nowMs - m_lastGunAimTickMs;
    dt = (float)delta / 1000.0f;
    if (dt > 0.05f)
      dt = 0.05f;
  }
  m_lastGunAimTickMs = nowMs;
  if (dt <= 0.0f)
    return;

  auto normAxis = [](int v) {
    if (v <= -32768) return -1.0f;
    if (v >= 32767) return 1.0f;
    return (float)v / 32767.0f;
  };

  auto axisAny = [&](int axisNum) {
    int best = 0;
    for (const auto& c : m_controllers)
    {
      const int v = AxisValueFor(c, axisNum);
      if (std::abs(v) > std::abs(best))
        best = v;
    }
    return best;
  };

  int ax = axisAny(AXIS_RX);
  int ay = axisAny(AXIS_RY);
  float fx = normAxis(ax);
  float fy = normAxis(ay);

  constexpr float deadzone = 0.18f;
  float mag = std::sqrt(fx * fx + fy * fy);
  if (mag < deadzone)
  {
    ax = axisAny(AXIS_X);
    ay = axisAny(AXIS_Y);
    fx = normAxis(ax);
    fy = normAxis(ay);
    mag = std::sqrt(fx * fx + fy * fy);
  }

  if (mag < deadzone)
    return;

  float scaled = (mag - deadzone) / (1.0f - deadzone);
  scaled = std::clamp(scaled, 0.0f, 1.0f);
  const float invMag = 1.0f / mag;
  fx = fx * invMag * scaled;
  fy = fy * invMag * scaled;

  const int w = (m_dispW > 0) ? (int)m_dispW : 496;
  const int h = (m_dispH > 0) ? (int)m_dispH : 384;
  const int x0 = (int)m_dispX;
  const int y0 = (int)m_dispY;
  const float baseSpeed = (float)std::max(w, h) * 1.6f;

  m_mouseX += (int)std::lround(fx * baseSpeed * dt);
  m_mouseY += (int)std::lround(fy * baseSpeed * dt);

  const int maxX = x0 + w - 1;
  const int maxY = y0 + h - 1;
  m_mouseX = std::clamp(m_mouseX, x0, maxX);
  m_mouseY = std::clamp(m_mouseY, y0, maxY);
}

void AndroidInputSystem::CloseControllers()
{
  for (auto& c : m_controllers)
  {
    if (c.controller)
    {
      SDL_GameControllerClose(c.controller);
      c.controller = nullptr;
    }
    if (c.joystick)
    {
      SDL_JoystickClose(c.joystick);
      c.joystick = nullptr;
    }
  }
  m_controllers.clear();
}

void AndroidInputSystem::RefreshControllers()
{
  CloseControllers();

  const int n = SDL_NumJoysticks();
  for (int i = 0; i < n; i++)
  {
    ControllerState state;
    SDL_Joystick* js = nullptr;

    if (SDL_IsGameController(i))
    {
      SDL_GameController* controller = SDL_GameControllerOpen(i);
      if (!controller)
      {
        const char* name = SDL_JoystickNameForIndex(i);
        SDL_JoystickGUID guid = SDL_JoystickGetDeviceGUID(i);
        char guidStr[64] = {};
        SDL_JoystickGetGUIDString(guid, guidStr, sizeof(guidStr));
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Failed to open game controller %d: %s (guid=%s) (%s)",
                     i + 1,
                     name ? name : "Unknown",
                     guidStr,
                     SDL_GetError());
        continue;
      }

      js = SDL_GameControllerGetJoystick(controller);
      if (!js)
      {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Failed to get joystick handle for controller %d: %s (%s)",
                     i + 1,
                     SDL_GameControllerName(controller) ? SDL_GameControllerName(controller) : "GameController",
                     SDL_GetError());
        SDL_GameControllerClose(controller);
        continue;
      }

      state.controller = controller;
      state.isGameController = true;
      std::memset(&state.details, 0, sizeof(state.details));

      const char* name = SDL_GameControllerName(controller);
      if (!name) name = "GameController";
      std::strncpy(state.details.name, name, MAX_NAME_LENGTH);
      state.details.name[MAX_NAME_LENGTH] = '\0';

      // Align with the desktop SDL "gamecontroller" path.
      state.details.numAxes = 6;
      state.details.numPOVs = 4;
      state.details.numButtons = 17;
      state.details.hasFFeedback = false;

      for (int a = 0; a < NUM_JOY_AXES; a++)
      {
        state.details.hasAxis[a] = false;
        state.details.axisHasFF[a] = false;
        std::strncpy(state.details.axisName[a], GetDefaultAxisName(a), MAX_NAME_LENGTH);
        state.details.axisName[a][MAX_NAME_LENGTH] = '\0';
      }

      state.details.hasAxis[AXIS_X] = true;
      state.details.hasAxis[AXIS_Y] = true;
      state.details.hasAxis[AXIS_Z] = true;
      state.details.hasAxis[AXIS_RX] = true;
      state.details.hasAxis[AXIS_RY] = true;
      state.details.hasAxis[AXIS_RZ] = true;
    }
    else
    {
      const char* name = SDL_JoystickNameForIndex(i);
      SDL_JoystickGUID guid = SDL_JoystickGetDeviceGUID(i);
      char guidStr[64] = {};
      SDL_JoystickGetGUIDString(guid, guidStr, sizeof(guidStr));
      SDL_Log("Unmapped joystick %d: %s (guid=%s). Using raw joystick mapping; add a mapping to %s for standardized layout.",
              i + 1,
              name ? name : "Unknown",
              guidStr,
              m_gameControllerMappingsPath.empty() ? "Config/gamecontrollerdb.txt" : m_gameControllerMappingsPath.c_str());

      js = SDL_JoystickOpen(i);
      if (!js)
      {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Failed to open joystick %d: %s (guid=%s) (%s)",
                     i + 1,
                     name ? name : "Unknown",
                     guidStr,
                     SDL_GetError());
        continue;
      }

      const int axes = SDL_JoystickNumAxes(js);
      const int hats = SDL_JoystickNumHats(js);
      const int buttons = SDL_JoystickNumButtons(js);

      // Some Android devices expose non-controller joysticks (e.g. sensors) that have no buttons or hats.
      // Ignore those so touch controls keep working and JOY1 doesn't get hijacked.
      if (buttons == 0 && hats == 0)
      {
        SDL_Log("Ignoring joystick %d: %s (guid=%s) (axes=%d, buttons=%d, hats=%d).",
                i + 1,
                name ? name : "Unknown",
                guidStr,
                axes,
                buttons,
                hats);
        SDL_JoystickClose(js);
        continue;
      }

      state.joystick = js;
      state.isGameController = false;
      std::memset(&state.details, 0, sizeof(state.details));

      const char* jsName = SDL_JoystickName(js);
      if (!jsName) jsName = "Joystick";
      std::strncpy(state.details.name, jsName, MAX_NAME_LENGTH);
      state.details.name[MAX_NAME_LENGTH] = '\0';

      state.details.numAxes = axes;
      state.details.numPOVs = hats;
      state.details.numButtons = buttons;
      state.details.hasFFeedback = false;

      for (int a = 0; a < NUM_JOY_AXES; a++)
      {
        state.details.hasAxis[a] = state.details.numAxes > a;
        state.details.axisHasFF[a] = false;
        std::strncpy(state.details.axisName[a], GetDefaultAxisName(a), MAX_NAME_LENGTH);
        state.details.axisName[a][MAX_NAME_LENGTH] = '\0';
      }
    }

    state.instanceId = SDL_JoystickInstanceID(js);
    m_controllers.push_back(state);
  }
}

int AndroidInputSystem::GetNumJoysticks()
{
  if (UseVirtualJoystick())
    return 1;
  return static_cast<int>(m_controllers.size());
}

const JoyDetails* AndroidInputSystem::GetJoyDetails(int joyNum)
{
  if (UseVirtualJoystick())
  {
    if (joyNum == ANY_JOYSTICK || joyNum == 0)
      return &m_virtualJoyDetails;
    return nullptr;
  }

  if (joyNum < 0 || joyNum >= static_cast<int>(m_controllers.size()))
    return nullptr;
  return &m_controllers[static_cast<size_t>(joyNum)].details;
}

int AndroidInputSystem::AxisValueFor(const ControllerState& c, int axisNum) const
{
  if (c.isGameController)
  {
    if (!c.controller)
      return 0;

    switch (axisNum)
    {
      case AXIS_X: return (int)SDL_GameControllerGetAxis(c.controller, SDL_CONTROLLER_AXIS_LEFTX);
      case AXIS_Y: return (int)SDL_GameControllerGetAxis(c.controller, SDL_CONTROLLER_AXIS_LEFTY);
      case AXIS_Z: return (int)SDL_GameControllerGetAxis(c.controller, SDL_CONTROLLER_AXIS_TRIGGERLEFT);
      case AXIS_RX: return (int)SDL_GameControllerGetAxis(c.controller, SDL_CONTROLLER_AXIS_RIGHTX);
      case AXIS_RY: return (int)SDL_GameControllerGetAxis(c.controller, SDL_CONTROLLER_AXIS_RIGHTY);
      case AXIS_RZ: return (int)SDL_GameControllerGetAxis(c.controller, SDL_CONTROLLER_AXIS_TRIGGERRIGHT);
      default: return 0;
    }
  }

  if (!c.joystick)
    return 0;

  if (axisNum < 0 || axisNum >= c.details.numAxes)
    return 0;

  return SDL_JoystickGetAxis(c.joystick, axisNum);
}

bool AndroidInputSystem::PovPressedFor(const ControllerState& c, int povNum, int povDir) const
{
  if (c.isGameController)
  {
    if (!c.controller)
      return false;

    switch (povDir)
    {
      case POV_UP: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_DPAD_UP) != 0;
      case POV_DOWN: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_DPAD_DOWN) != 0;
      case POV_LEFT: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_DPAD_LEFT) != 0;
      case POV_RIGHT: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_DPAD_RIGHT) != 0;
      default: return false;
    }
  }

  if (!c.joystick)
    return false;
  if (povNum < 0 || povNum >= c.details.numPOVs)
    return false;

  const uint8_t hat = SDL_JoystickGetHat(c.joystick, povNum);
  switch (povDir)
  {
    case POV_UP: return (hat & SDL_HAT_UP) != 0;
    case POV_DOWN: return (hat & SDL_HAT_DOWN) != 0;
    case POV_LEFT: return (hat & SDL_HAT_LEFT) != 0;
    case POV_RIGHT: return (hat & SDL_HAT_RIGHT) != 0;
    default: return false;
  }
}

bool AndroidInputSystem::ButtonPressedFor(const ControllerState& c, int butNum) const
{
  if (c.isGameController)
  {
    if (!c.controller)
      return false;

    // Match the desktop SDLInputSystem "useGameController" mapping.
    switch (butNum)
    {
      case 0: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_A) != 0;
      case 1: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_B) != 0;
      case 2: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_X) != 0;
      case 3: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_Y) != 0;
      case 4: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_LEFTSHOULDER) != 0;
      case 5: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_RIGHTSHOULDER) != 0;
      case 6: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_BACK) != 0;
      case 7: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_START) != 0;
      case 8: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_LEFTSTICK) != 0;
      case 9: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_RIGHTSTICK) != 0;
      case 10: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_PADDLE1) != 0;
      case 11: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_PADDLE2) != 0;
      case 12: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_GUIDE) != 0;
      case 13: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_PADDLE3) != 0;
      case 14: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_PADDLE4) != 0;
      case 15: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_MISC1) != 0;
      case 16: return SDL_GameControllerGetButton(c.controller, SDL_CONTROLLER_BUTTON_TOUCHPAD) != 0;
      default: return false;
    }
  }

  if (!c.joystick)
    return false;
  if (butNum < 0 || butNum >= c.details.numButtons)
    return false;
  return SDL_JoystickGetButton(c.joystick, butNum) != 0;
}

int AndroidInputSystem::GetJoyAxisValue(int joyNum, int axisNum)
{
  if (m_gunStickAimEnabled && m_virtualAnalogJoystickEnabled && !UseVirtualJoystick() &&
      (axisNum == AXIS_X || axisNum == AXIS_Y || axisNum == AXIS_RX || axisNum == AXIS_RY))
  {
    return 0;
  }

  // Analog-gun games often map the gun axis to JOY1_X/Y, which can override our
  // mouse/touch/stick-aim path. Suppress raw joystick axes so the gun uses MOUSE_*.
  if (m_gunStickAimEnabled && m_virtualAnalogGunEnabled && !UseVirtualJoystick() &&
      (axisNum == AXIS_X || axisNum == AXIS_Y || axisNum == AXIS_RX || axisNum == AXIS_RY))
  {
    return 0;
  }

  if (UseVirtualJoystick())
  {
    if (axisNum == AXIS_X)
      return m_virtualJoyX.load(std::memory_order_relaxed);
    if (axisNum == AXIS_Y && m_virtualJoyDetails.hasAxis[AXIS_Y])
      return m_virtualJoyY;
    return 0;
  }

  if (m_controllers.empty())
    return 0;

  if (joyNum == ANY_JOYSTICK)
  {
    int best = 0;
    for (const auto& c : m_controllers)
    {
      const int v = AxisValueFor(c, axisNum);
      if (std::abs(v) > std::abs(best))
        best = v;
    }
    return best;
  }

  if (joyNum < 0 || joyNum >= static_cast<int>(m_controllers.size()))
    return 0;

  return AxisValueFor(m_controllers[static_cast<size_t>(joyNum)], axisNum);
}

bool AndroidInputSystem::IsJoyPOVInDir(int joyNum, int povNum, int povDir)
{
  if (m_controllers.empty())
    return false;

  if (joyNum == ANY_JOYSTICK)
  {
    for (const auto& c : m_controllers)
    {
      if (PovPressedFor(c, povNum, povDir))
        return true;
    }
    return false;
  }

  if (joyNum < 0 || joyNum >= static_cast<int>(m_controllers.size()))
    return false;

  return PovPressedFor(m_controllers[static_cast<size_t>(joyNum)], povNum, povDir);
}

bool AndroidInputSystem::IsJoyButPressed(int joyNum, int butNum)
{
  if (m_controllers.empty())
    return false;

  if (joyNum == ANY_JOYSTICK)
  {
    for (const auto& c : m_controllers)
    {
      if (ButtonPressedFor(c, butNum))
        return true;
    }
    return false;
  }

  if (joyNum < 0 || joyNum >= static_cast<int>(m_controllers.size()))
    return false;

  return ButtonPressedFor(m_controllers[static_cast<size_t>(joyNum)], butNum);
}

SDL_Scancode AndroidInputSystem::ScancodeFromSupermodelKeyName(const char* keyName) const
{
  if (!keyName || !keyName[0])
    return SDL_SCANCODE_UNKNOWN;

  // Fast-path common names used by our defaults.
  if (strcmp(keyName, "UP") == 0) return SDL_SCANCODE_UP;
  if (strcmp(keyName, "DOWN") == 0) return SDL_SCANCODE_DOWN;
  if (strcmp(keyName, "LEFT") == 0) return SDL_SCANCODE_LEFT;
  if (strcmp(keyName, "RIGHT") == 0) return SDL_SCANCODE_RIGHT;
  if (strcmp(keyName, "RETURN") == 0) return SDL_SCANCODE_RETURN;
  if (strcmp(keyName, "ESCAPE") == 0) return SDL_SCANCODE_ESCAPE;
  if (strcmp(keyName, "SPACE") == 0) return SDL_SCANCODE_SPACE;

  // Digits and letters match SDL key names.
  if ((keyName[0] >= '0' && keyName[0] <= '9') && keyName[1] == '\0')
    return SDL_GetScancodeFromName(keyName);
  if ((keyName[0] >= 'A' && keyName[0] <= 'Z') && keyName[1] == '\0')
    return SDL_GetScancodeFromName(keyName);

  // Function keys.
  if (keyName[0] == 'F' && keyName[1] >= '1' && keyName[1] <= '9')
    return SDL_GetScancodeFromName(keyName);

  // Try SDL's name lookup as a fallback (works for many names, albeit with different casing).
  return SDL_GetScancodeFromName(keyName);
}

SDL_Scancode AndroidInputSystem::ParseFirstKeyboardScancode(const std::string& mapping, const AndroidInputSystem& self)
{
  // mapping examples:
  // - "KEY_F2"
  // - "KEY_RIGHT,JOY1_XAXIS_POS"
  // - "KEY_ALT+KEY_R"
  // - "!KEY_ALT+KEY_P"
  std::string s = mapping;
  size_t start = 0;
  while (start < s.size())
  {
    // Find next token boundary.
    while (start < s.size() && (s[start] == ' ' || s[start] == '\t' || s[start] == ',' || s[start] == '+'))
      start++;
    if (start >= s.size()) break;

    size_t end = start;
    while (end < s.size() && s[end] != ' ' && s[end] != '\t' && s[end] != ',' && s[end] != '+')
      end++;

    std::string tok = s.substr(start, end - start);
    if (!tok.empty() && tok[0] == '!')
      tok.erase(tok.begin());

    if (tok.rfind("KEY_", 0) == 0 && tok.size() > 4)
    {
      const std::string keyName = tok.substr(4);
      return self.ScancodeFromSupermodelKeyName(keyName.c_str());
    }

    start = end;
  }

  return SDL_SCANCODE_UNKNOWN;
}

int AndroidInputSystem::GetKeyIndex(const char* keyName)
{
  const SDL_Scancode sc = ScancodeFromSupermodelKeyName(keyName);
  return (sc == SDL_SCANCODE_UNKNOWN) ? -1 : (int)sc;
}

const char* AndroidInputSystem::GetKeyName(int keyIndex)
{
  if (keyIndex <= SDL_SCANCODE_UNKNOWN || keyIndex >= SDL_NUM_SCANCODES)
    return "";
  return SDL_GetScancodeName(static_cast<SDL_Scancode>(keyIndex));
}

bool AndroidInputSystem::IsKeyPressed(int /*kbdNum*/, int keyIndex)
{
  if (keyIndex <= SDL_SCANCODE_UNKNOWN || keyIndex >= SDL_NUM_SCANCODES)
    return false;
  return m_keys[static_cast<size_t>(keyIndex)] != 0;
}
