#pragma once

#include <SDL.h>

#include <atomic>
#include <cstdint>
#include <unordered_map>
#include <string>
#include <vector>

#include "Inputs/InputSystem.h"
#include "Inputs/Input.h"
#include "Util/NewConfig.h"

// Minimal SDL-backed input system for Android.
// - Uses SDL scancodes as key indices.
// - Provides simple touch controls by synthesizing "keyboard" scancode presses.
class AndroidInputSystem final : public CInputSystem
{
public:
  AndroidInputSystem();
  ~AndroidInputSystem() override;

  void SetGunTouchEnabled(bool enabled);
  void SetGunStickAimEnabled(bool enabled);
  void SetVirtualWheelEnabled(bool enabled);
  void SetVirtualShifterMode(bool shift4, bool shiftUpDown);
  void SetVirtualAnalogGunEnabled(bool enabled);
  void SetVirtualAnalogJoystickEnabled(bool enabled);
  void SetGyroSteer(float steer); // [-1..1]

  // Called from the SDL event loop (main thread).
  void HandleEvent(const SDL_Event& ev);

  // Update touch/controller bindings from the current config (e.g. Supermodel.ini overrides).
  void ApplyConfig(const Util::Config::Node& config);

  bool InitializeSystem() override;
  int GetKeyIndex(const char* keyName) override;
  const char* GetKeyName(int keyIndex) override;
  bool IsKeyPressed(int /*kbdNum*/, int keyIndex) override;

  int GetMouseAxisValue(int mseNum, int axisNum) override;
  int GetMouseWheelDir(int mseNum) override;
  bool IsMouseButPressed(int mseNum, int butNum) override;

  int GetJoyAxisValue(int joyNum, int axisNum) override;
  bool IsJoyPOVInDir(int joyNum, int povNum, int povDir) override;
  bool IsJoyButPressed(int joyNum, int butNum) override;
  bool ProcessForceFeedbackCmd(int /*joyNum*/, int /*axisNum*/, ForceFeedbackCmd /*ffCmd*/) override { return false; }

  int GetNumKeyboards() override { return 1; }
  int GetNumMice() override;
  int GetNumJoysticks() override;

  const KeyDetails* GetKeyDetails(int /*kbdNum*/) override { return nullptr; }
  const MouseDetails* GetMouseDetails(int mseNum) override;
  const JoyDetails* GetJoyDetails(int joyNum) override;

  bool Poll() override;

  void SetMouseVisibility(bool /*visible*/) override {}
  void GrabMouse() override {}
  void UngrabMouse() override {}

private:
  static constexpr int kMouseButtons = 5;

  struct ControllerState {
    SDL_GameController* controller = nullptr;
    SDL_Joystick* joystick = nullptr;
    SDL_JoystickID instanceId = 0;
    JoyDetails details{};
    bool isGameController = false;
  };

  struct DualScancode {
    SDL_Scancode a = SDL_SCANCODE_UNKNOWN;
    SDL_Scancode b = SDL_SCANCODE_UNKNOWN;
  };

  struct HeldDirKeys {
    DualScancode primary{};
    DualScancode secondary{};
  };

  void RefreshControllers();
  void CloseControllers();

  bool UseVirtualWheel() const;
  bool UseVirtualJoystick() const;
  void SetVirtualSteerFromEncoded(float encodedX);
  void SetVirtualSteerFromSteer(float steer);
  void SetVirtualJoyFromNormalized(float x, float y);
  void SetVirtualStickFromNormalized(float x, float y);
  void UpdateVirtualJoystickDescriptor();

  void SetKey(SDL_Scancode sc, bool down);
  void PulseKey(SDL_Scancode sc, uint32_t durationMs);
  void SetKeys(const DualScancode& sc, bool down);
  void PulseKeys(const DualScancode& sc, uint32_t durationMs);

  void HandleKeyEvent(const SDL_KeyboardEvent& key, bool down);
  void HandleControllerButtonEvent(const SDL_ControllerButtonEvent& btn, bool down);
  void HandleTouch(const SDL_TouchFingerEvent& tf, bool down);
  void HandleTouchMotion(const SDL_TouchFingerEvent& tf);

  int AxisValueFor(const ControllerState& c, int axisNum) const;
  bool ButtonPressedFor(const ControllerState& c, int butNum) const;
  bool PovPressedFor(const ControllerState& c, int povNum, int povDir) const;

  void EnsureGameControllerMappingsLoaded();

  void SetMouseButton(int butNum, bool down);
  void PulseMouseButton(int butNum, uint32_t durationMs);
  void SetMousePosFromNormalized(float x, float y);
  void CenterMouse();
  void UpdateGunStickAim(uint32_t nowMs);

  SDL_Scancode ScancodeFromSupermodelKeyName(const char* keyName) const;
  static SDL_Scancode ParseFirstKeyboardScancode(const std::string& mapping, const AndroidInputSystem& self);

  // Configurable bindings (default to our original hardcoded mapping).
  DualScancode m_touchCoin{SDL_SCANCODE_5, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchStart{SDL_SCANCODE_1, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchService{SDL_SCANCODE_F1, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchTest{SDL_SCANCODE_F2, SDL_SCANCODE_UNKNOWN};

  DualScancode m_touchJoyUp{SDL_SCANCODE_UP, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchJoyDown{SDL_SCANCODE_DOWN, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchJoyLeft{SDL_SCANCODE_LEFT, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchJoyRight{SDL_SCANCODE_RIGHT, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchSteerLeft{SDL_SCANCODE_LEFT, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchSteerRight{SDL_SCANCODE_RIGHT, SDL_SCANCODE_UNKNOWN};

  DualScancode m_touchThrottle{SDL_SCANCODE_W, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchBrake{SDL_SCANCODE_X, SDL_SCANCODE_UNKNOWN};

  DualScancode m_touchShiftUp{SDL_SCANCODE_I, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchShiftDown{SDL_SCANCODE_K, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchShift1{SDL_SCANCODE_7, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchShift2{SDL_SCANCODE_8, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchShift3{SDL_SCANCODE_9, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchShift4{SDL_SCANCODE_0, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchShiftN{SDL_SCANCODE_6, SDL_SCANCODE_UNKNOWN};

  DualScancode m_touchViewChange{SDL_SCANCODE_A, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchVr1{SDL_SCANCODE_A, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchVr2{SDL_SCANCODE_S, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchVr3{SDL_SCANCODE_D, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchVr4{SDL_SCANCODE_F, SDL_SCANCODE_UNKNOWN};

  DualScancode m_touchPunch{SDL_SCANCODE_A, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchKick{SDL_SCANCODE_S, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchGuard{SDL_SCANCODE_D, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchEscape{SDL_SCANCODE_F, SDL_SCANCODE_UNKNOWN};

  DualScancode m_touchSpikeShift{SDL_SCANCODE_A, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchSpikeBeat{SDL_SCANCODE_S, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchSpikeCharge{SDL_SCANCODE_D, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchSpikeJump{SDL_SCANCODE_F, SDL_SCANCODE_UNKNOWN};

  // Virtual-On (twin joystick) macros/buttons.
  DualScancode m_touchTwinJump{SDL_SCANCODE_E, SDL_SCANCODE_UNKNOWN};
  // Map a single "Boost" button to Turbo1 by default (Virtual-On has separate left/right turbo buttons).
  DualScancode m_touchTwinBoost{SDL_SCANCODE_Z, SDL_SCANCODE_UNKNOWN};

  DualScancode m_touchFishingCast{SDL_SCANCODE_Z, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchFishingSelect{SDL_SCANCODE_X, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchFishingReel{SDL_SCANCODE_SPACE, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchFishingTension{SDL_SCANCODE_T, SDL_SCANCODE_UNKNOWN};

  DualScancode m_touchCrosshairs{SDL_SCANCODE_F8, SDL_SCANCODE_UNKNOWN};

  DualScancode m_touchMagPedal1{SDL_SCANCODE_A, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchMagPedal2{SDL_SCANCODE_S, SDL_SCANCODE_UNKNOWN};

  DualScancode m_touchSkiPollLeft{SDL_SCANCODE_A, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchSkiPollRight{SDL_SCANCODE_S, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchSkiSelect1{SDL_SCANCODE_Q, SDL_SCANCODE_UNKNOWN};
  DualScancode m_touchSkiSelect2{SDL_SCANCODE_W, SDL_SCANCODE_UNKNOWN};

  // When false, physical controller Start/Back/Guide/D-pad no longer synthesize
  // keyboard presses, so only the JOY mappings in Supermodel.ini apply.
  bool m_controllerShortcuts = true;

  bool m_gunTouchEnabled = false;
  bool m_gunStickAimEnabled = false;
  uint32_t m_lastGunAimTickMs = 0;
  SDL_FingerID m_gunFinger = 0;
  bool m_gunFingerActive = false;
  bool m_mousePadActive = false;
  SDL_FingerID m_mousePadFinger = 0;
  float m_mousePadLastX = 0.0f;
  float m_mousePadLastY = 0.0f;

  bool m_virtualWheelEnabled = false;
  SDL_FingerID m_wheelFinger = 0;
  bool m_wheelFingerActive = false;
  std::atomic<int> m_virtualJoyX{0};
  int m_virtualJoyY = 0;
  JoyDetails m_virtualJoyDetails{};

  bool m_virtualShifterShift4 = false;
  bool m_virtualShifterUpDown = false;
  int m_lastVirtualGear = -1;

  bool m_virtualAnalogGunEnabled = false;
  bool m_virtualAnalogJoystickEnabled = false;

  MouseDetails m_mouseDetails{};
  int m_mouseX = 0;
  int m_mouseY = 0;
  int m_mouseWheelDir = 0;
  uint8_t m_mouseButtons[kMouseButtons]{};
  uint32_t m_mouseButtonPulseUntilMs[kMouseButtons]{};

  // Lightgun reload behavior:
  // - If InputAutoTrigger is enabled, we only need to assert Offscreen; the core will
  //   synthesize trigger timing. We also temporarily suppress the trigger so reload
  //   happens in the correct order even if the user is holding the screen to aim/fire.
  bool m_gunAutoTrigger = false;
  uint32_t m_suppressGunTriggerUntilMs = 0;
  uint32_t m_pendingReloadTriggerAtMs = 0;

  std::vector<ControllerState> m_controllers;
  std::vector<uint8_t> m_keys; // indexed by SDL_Scancode
  std::unordered_map<SDL_FingerID, HeldDirKeys> m_fingerHeldDir;
  std::unordered_map<SDL_FingerID, DualScancode> m_fingerHeldKey;
  std::unordered_map<SDL_Scancode, uint32_t> m_pulseUntilMs;

  std::string m_gameControllerMappingsPath;
  bool m_gameControllerMappingsLoaded = false;
};
