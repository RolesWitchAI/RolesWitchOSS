/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppMinimumDelegate.hpp"
#include <iostream>
#include <GLES2/gl2.h>
#include "LAppMinimumView.hpp"
#include "LAppPal.hpp"
#include "LAppDefine.hpp"
#include "LAppMinimumLive2DManager.hpp"
#include "LAppTextureManager.hpp"
#include "JniBridgeC.hpp"

using namespace Csm;
using namespace std;
using namespace LAppDefine;

namespace {
    LAppMinimumDelegate* s_instance = nullptr;
}

LAppMinimumDelegate* LAppMinimumDelegate::GetInstance()
{
    if (!s_instance)
    {
        s_instance = new LAppMinimumDelegate();
    }

    return s_instance;
}

void LAppMinimumDelegate::ReleaseInstance()
{
    if (s_instance)
    {
        delete s_instance;
    }

    s_instance = nullptr;
}


void LAppMinimumDelegate::OnStart()
{
    // Already initialized - skip to prevent double allocation
    if (_textureManager && _view) {
        return;
    }

    _textureManager = new LAppTextureManager();
    _view = new LAppMinimumView();
    LAppPal::UpdateTime();
}

void LAppMinimumDelegate::OnPause()
{
    _isActive = false;
}

void LAppMinimumDelegate::OnResume()
{
    _isActive = true;
    LAppPal::UpdateTime();
}

void LAppMinimumDelegate::OnStop()
{
    if (_view)
    {
        delete _view;
        _view = nullptr;
    }
    if (_textureManager)
    {
        delete _textureManager;
        _textureManager = nullptr;
    }

    // Reset initialization flag for next time
    _viewInitialized = false;
    _width = 0;
    _height = 0;

    // リソースを解放
    LAppMinimumLive2DManager::ReleaseInstance();

    CubismFramework::Dispose();

    // Clean up and prepare for next initialization
    // This is needed so CubismFramework::Initialize() can be called again
    CubismFramework::CleanUp();
}

void LAppMinimumDelegate::OnDestroy()
{
    ReleaseInstance();
}

void LAppMinimumDelegate::Run()
{
    // 時間更新
    LAppPal::UpdateTime();

    // Clear to light gray to match app theme (Material 3 default surface color)
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glClearDepthf(1.0f);

    // Initialize view if it exists but hasn't been initialized yet
    // This can happen when OnSurfaceChanged is called before OnStart
    if (_view && !_viewInitialized && _width > 0 && _height > 0)
    {
        _view->Initialize(_width, _height);
        _view->InitializeSprite();
        _viewInitialized = true;
    }

    //描画更新
    if (_view)
    {
        _view->Render();
    }

    if(!_isActive)
    {
        JniBridgeC::MoveTaskToBack();
    }
}

void LAppMinimumDelegate::OnSurfaceCreate()
{
    // A new GL surface means a new GL context — all previous GL resources
    // (textures, shaders, buffers) are invalid.  If stale state exists
    // (e.g. OnStop was queued on a dead GL thread and never ran), tear it
    // down before re-initializing.
    if (_view || _textureManager)
    {
        if (_view)      { delete _view;           _view = nullptr; }
        if (_textureManager) { delete _textureManager; _textureManager = nullptr; }
        _viewInitialized = false;
        _width = 0;
        _height = 0;

        LAppMinimumLive2DManager::ReleaseInstance();

        if (CubismFramework::IsInitialized())
        {
            CubismFramework::Dispose();
        }
        CubismFramework::CleanUp();
    }

    // Fresh allocation for this GL context
    _textureManager = new LAppTextureManager();
    _view = new LAppMinimumView();

    //テクスチャサンプリング設定
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);

    //透過設定
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

    //Initialize cubism
    CubismFramework::StartUp(&_cubismAllocator, &_cubismOption);
    CubismFramework::Initialize();
}

void LAppMinimumDelegate::OnSurfaceChanged(float width, float height)
{
    glViewport(0, 0, width, height);
    _width = width;
    _height = height;

    //AppViewの初期化 - _view might not be ready yet if OnStart hasn't been called
    if (_view)
    {
        _view->Initialize(width, height);
        _view->InitializeSprite();
        _viewInitialized = true;
    }

    _isActive = true;
}

LAppMinimumDelegate::LAppMinimumDelegate():
    _cubismOption(),
    _captured(false),
    _mouseX(0.0f),
    _mouseY(0.0f),
    _isActive(true),
    _textureManager(nullptr),
    _view(nullptr),
    _viewInitialized(false),
    _width(0),
    _height(0)
{
    // Setup Cubism
    _cubismOption.LogFunction = LAppPal::PrintMessageLn;
    _cubismOption.LoggingLevel = LAppDefine::CubismLoggingLevel;
    _cubismOption.LoadFileFunction = LAppPal::LoadFileAsBytes;
    _cubismOption.ReleaseBytesFunction = LAppPal::ReleaseBytes;
    CubismFramework::CleanUp();
    CubismFramework::StartUp(&_cubismAllocator, &_cubismOption);
}

LAppMinimumDelegate::~LAppMinimumDelegate()
{
}

void LAppMinimumDelegate::OnTouchBegan(double x, double y)
{
    _mouseX = static_cast<float>(x);
    _mouseY = static_cast<float>(y);

    if (_view)
    {
        _captured = true;
        _view->OnTouchesBegan(_mouseX, _mouseY);
    }
}

void LAppMinimumDelegate::OnTouchEnded(double x, double y)
{
    _mouseX = static_cast<float>(x);
    _mouseY = static_cast<float>(y);

    if (_view)
    {
        _captured = false;
        _view->OnTouchesEnded(_mouseX, _mouseY);
    }
}

void LAppMinimumDelegate::OnTouchMoved(double x, double y)
{
    _mouseX = static_cast<float>(x);
    _mouseY = static_cast<float>(y);

    if (_captured && _view)
    {
        _view->OnTouchesMoved(_mouseX, _mouseY);
    }
}
