/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#pragma once

#include <string>
#include <functional>
#include <CubismFramework.hpp>
#include <CubismModelSettingJson.hpp>
#include <Type/csmRectF.hpp>
#include <Rendering/OpenGL/CubismOffscreenSurface_OpenGLES2.hpp>
#include "LAppModel_Common.hpp"

class LAppMinimumModel : public LAppModel_Common
{
public:
    LAppMinimumModel();
    LAppMinimumModel(const std::string modelDirectoryName, const std::string currentModelDirectory);
    virtual ~LAppMinimumModel();

    std::string MakeAssetPath(const std::string & assetFileName);
    void LoadAssets(const std::string & fiileName, const std::function<void(Csm::csmByte*, Csm::csmSizeInt)>& afterLoadCallback);
    void ReloadRenderer();
    void Update();
    void Draw(Csm::CubismMatrix44& matrix);
    Csm::CubismMotionQueueEntryHandle StartMotion(const Csm::csmChar* group, Csm::csmInt32 no, Csm::csmInt32 priority);
    Csm::CubismMotionQueueEntryHandle StartRandomMotion(const Csm::csmChar* group, Csm::csmInt32 priority);
    void SetExpression(const Csm::csmChar* expressionID);
    void SetLipSyncValue(float value);  // Set lip-sync value (0.0 to 1.0) for mouth animation
    virtual void MotionEventFired(const Live2D::Cubism::Framework::csmString& eventValue);
    void SetupModel();
    Csm::Rendering::CubismOffscreenSurface_OpenGLES2& GetRenderBuffer();

private:
    void SetupTextures();
    void PreloadMotionGroup(const Csm::csmChar* group);
    void ReleaseMotionGroup(const Csm::csmChar* group) const;
    void ReleaseMotions();
    void ReleaseExpressions();

    Csm::CubismModelSettingJson* _modelJson;
    std::string _modelDirName;
    std::string _currentModelDirectory;
    Csm::csmFloat32 _userTimeSeconds;
    Csm::csmVector<Csm::CubismIdHandle> _eyeBlinkIds;
    Csm::csmVector<Csm::CubismIdHandle> _lipSyncIds;
    Csm::csmFloat32 _lipSyncValue;  ///< Current lip-sync value for mouth animation
    Csm::csmMap<Csm::csmString, Csm::ACubismMotion*> _motions;
    Csm::csmMap<Csm::csmString, Csm::ACubismMotion*> _expressions;
    const Csm::CubismId* _idParamAngleX;
    const Csm::CubismId* _idParamAngleY;
    const Csm::CubismId* _idParamAngleZ;
    const Csm::CubismId* _idParamBodyAngleX;
    const Csm::CubismId* _idParamEyeBallX;
    const Csm::CubismId* _idParamEyeBallY;

    Csm::Rendering::CubismOffscreenSurface_OpenGLES2 _renderBuffer;
};
