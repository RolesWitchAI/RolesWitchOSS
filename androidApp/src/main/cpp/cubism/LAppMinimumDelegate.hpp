/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#pragma once

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include "LAppAllocator_Common.hpp"

class LAppMinimumView;
class LAppTextureManager;

class LAppMinimumDelegate
{
public:
    static LAppMinimumDelegate* GetInstance();
    static void ReleaseInstance();

    void OnStart();
    void OnPause();
    void OnResume();
    void OnStop();
    void OnDestroy();
    void OnSurfaceCreate();
    void OnSurfaceChanged(float width, float height);
    void Run();

    void OnTouchBegan(double x, double y);
    void OnTouchEnded(double x, double y);
    void OnTouchMoved(double x, double y);

    LAppTextureManager* GetTextureManager() { return _textureManager; }
    int GetWindowWidth() { return _width; }
    int GetWindowHeight() { return _height; }
    void DeActivateApp() { _isActive = false; }
    LAppMinimumView* GetView() { return _view; }

private:
    LAppMinimumDelegate();
    ~LAppMinimumDelegate();

    LAppAllocator_Common _cubismAllocator;
    Csm::CubismFramework::Option _cubismOption;
    LAppTextureManager* _textureManager;
    LAppMinimumView* _view;
    int _width;
    int _height;
    bool _captured;
    bool _isActive;
    bool _viewInitialized;  // Track if view has been initialized
    float _mouseY;
    float _mouseX;
};
