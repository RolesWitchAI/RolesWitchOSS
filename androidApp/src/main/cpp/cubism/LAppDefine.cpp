/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppDefine.hpp"
#include <CubismFramework.hpp>

namespace LAppDefine {

    using namespace Csm;

    const csmFloat32 ViewScale = 1.0f;
    const csmFloat32 ViewMaxScale = 2.0f;
    const csmFloat32 ViewMinScale = 0.8f;

    const csmFloat32 ViewLogicalLeft = -1.0f;
    const csmFloat32 ViewLogicalRight = 1.0f;
    const csmFloat32 ViewLogicalBottom = -1.0f;
    const csmFloat32 ViewLogicalTop = 1.0f;

    const csmFloat32 ViewLogicalMaxLeft = -2.0f;
    const csmFloat32 ViewLogicalMaxRight = 2.0f;
    const csmFloat32 ViewLogicalMaxBottom = -2.0f;
    const csmFloat32 ViewLogicalMaxTop = 2.0f;

    const csmChar* ResourcesPath = "";

    const csmChar* BackImageName = "";
    const csmChar* GearImageName = "";
    const csmChar* PowerImageName = "";

    const csmChar* ShaderPath = "Shaders/";
    const csmChar* VertShaderName = "VertSprite.vert";
    const csmChar* FragShaderName = "FragSprite.frag";

    const csmChar* ModelDir[] = {
        "hibiki",
        "haru",
        "hiyori_pro_en",
        "mao_pro_en"
    };
    const csmInt32 ModelDirSize = sizeof(ModelDir) / sizeof(const csmChar*);

    const csmChar* MotionGroupIdle = "Idle";
    const csmChar* MotionGroupTapBody = "Tap";       // Changed to "Tap" to match model definitions
    const csmChar* MotionGroupSpeaking = "Tap";      // Reuse Tap for speaking
    const csmChar* MotionGroupThinking = "Idle";     // Use idle motions for thinking
    const csmChar* MotionGroupListening = "Idle";    // Use idle motions for listening

    const csmChar* HitAreaNameHead = "Head";
    const csmChar* HitAreaNameBody = "Body";

    const csmInt32 PriorityNone = 0;
    const csmInt32 PriorityIdle = 1;
    const csmInt32 PriorityNormal = 2;
    const csmInt32 PriorityForce = 3;

    const csmBool DebugLogEnable = true;
    const csmBool DebugTouchLogEnable = false;

    const CubismFramework::Option::LogLevel CubismLoggingLevel = CubismFramework::Option::LogLevel_Verbose;
}
