/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppMinimumModel.hpp"
#include <fstream>
#include <vector>
#include <CubismModelSettingJson.hpp>
#include <Motion/CubismMotion.hpp>
#include <Physics/CubismPhysics.hpp>
#include <CubismDefaultParameterId.hpp>
#include <Rendering/OpenGL/CubismRenderer_OpenGLES2.hpp>
#include <Utils/CubismString.hpp>
#include <Id/CubismIdManager.hpp>
#include <Motion/CubismMotionQueueEntry.hpp>
#include "LAppDefine.hpp"
#include "LAppPal.hpp"
#include "LAppTextureManager.hpp"
#include "LAppMinimumDelegate.hpp"

using namespace Live2D::Cubism::Framework;
using namespace Live2D::Cubism::Framework::DefaultParameterId;
using namespace LAppDefine;

LAppMinimumModel::LAppMinimumModel()
    : LAppModel_Common()
    , _modelJson(nullptr)
    , _userTimeSeconds(0.0f)
    , _lipSyncValue(0.0f)
{
    if (DebugLogEnable)
    {
        _debugMode = true;
    }

    _idParamAngleX = CubismFramework::GetIdManager()->GetId(ParamAngleX);
    _idParamAngleY = CubismFramework::GetIdManager()->GetId(ParamAngleY);
    _idParamAngleZ = CubismFramework::GetIdManager()->GetId(ParamAngleZ);
    _idParamBodyAngleX = CubismFramework::GetIdManager()->GetId(ParamBodyAngleX);
    _idParamEyeBallX = CubismFramework::GetIdManager()->GetId(ParamEyeBallX);
    _idParamEyeBallY = CubismFramework::GetIdManager()->GetId(ParamEyeBallY);
}

LAppMinimumModel::LAppMinimumModel(const std::string modelDirectoryName,const std::string currentModelDirectory)
        : LAppModel_Common(),_modelDirName(modelDirectoryName), _currentModelDirectory(currentModelDirectory), _modelJson(nullptr), _userTimeSeconds(0.0f), _lipSyncValue(0.0f)
{
    if (DebugLogEnable)
    {
        _debugMode = true;
    }

    _idParamAngleX = CubismFramework::GetIdManager()->GetId(ParamAngleX);
    _idParamAngleY = CubismFramework::GetIdManager()->GetId(ParamAngleY);
    _idParamAngleZ = CubismFramework::GetIdManager()->GetId(ParamAngleZ);
    _idParamBodyAngleX = CubismFramework::GetIdManager()->GetId(ParamBodyAngleX);
    _idParamEyeBallX = CubismFramework::GetIdManager()->GetId(ParamEyeBallX);
    _idParamEyeBallY = CubismFramework::GetIdManager()->GetId(ParamEyeBallY);
}

LAppMinimumModel::~LAppMinimumModel()
{
    ReleaseMotions();
    ReleaseExpressions();

    if (_modelJson)
    {
        for (csmInt32 i = 0; i < _modelJson->GetMotionGroupCount(); i++)
        {
            const csmChar* group = _modelJson->GetMotionGroupName(i);
            ReleaseMotionGroup(group);
        }
        delete _modelJson;
        _modelJson = nullptr;
    }
}

std::string LAppMinimumModel::MakeAssetPath(const std::string &assetFileName)
{
    return _currentModelDirectory + assetFileName;
}

namespace {
    // Case- *and underscore-insensitive* substring match. Mouth parameter ids differ
    // between models in both ways at once: hibiki and haru say PARAM_MOUTH_OPEN_Y,
    // hiyori says ParamMouthOpenY — a match that respected underscores found the
    // first spelling and silently missed the second (hiyori never lip-synced).
    bool ContainsIgnoreCase(const csmChar* haystack, const csmChar* needle)
    {
        if (!haystack || !needle) return false;

        for (const csmChar* h = haystack; *h; ++h)
        {
            if (*h == '_') continue;
            const csmChar* hh = h;
            const csmChar* nn = needle;
            while (*hh && *nn)
            {
                if (*hh == '_') { ++hh; continue; }
                if (*nn == '_') { ++nn; continue; }
                char a = *hh;
                char b = *nn;
                if (a >= 'A' && a <= 'Z') a = static_cast<char>(a - 'A' + 'a');
                if (b >= 'A' && b <= 'Z') b = static_cast<char>(b - 'A' + 'a');
                if (a != b) break;
                ++hh;
                ++nn;
            }
            if (*nn == '\0') return true;
        }
        return *needle == '\0';
    }
}

void LAppMinimumModel::LoadAssets(const std::string & fiileName, const std::function<void(Csm::csmByte*, Csm::csmSizeInt)>& afterLoadCallback)
{
    Csm::csmSizeInt bufferSize = 0;
    Csm::csmByte* buffer = nullptr;

    if (fiileName.empty())
    {
        return;
    }

    // バッファの設定
    buffer = LAppPal::LoadFileAsBytes(MakeAssetPath(fiileName).c_str(), &bufferSize);

    // コールバック関数の呼び出し
    afterLoadCallback(buffer, bufferSize);

    // バッファの解放
    LAppPal::ReleaseBytes(buffer);
}


void LAppMinimumModel::SetupModel()
{
    _updating = true;
    _initialized = false;

    LAppPal::PrintLogLn("[DEBUG] SetupModel starting for model: %s", _modelDirName.c_str());

    // モデルの設定データをJsonファイルから読み込み
    LoadAssets(_modelDirName + ".model3.json", [=](Csm::csmByte* buffer, Csm::csmSizeInt bufferSize) {
        _modelJson = new Csm::CubismModelSettingJson(buffer, bufferSize);
        LAppPal::PrintLogLn("[DEBUG] Model3.json loaded");
    });
    // モデルの設定データからモデルデータを読み込み
    LoadAssets(_modelJson->GetModelFileName(), [=](Csm::csmByte* buffer, Csm::csmSizeInt bufferSize) {
        LoadModel(buffer, bufferSize);
        LAppPal::PrintLogLn("[DEBUG] Moc3 file loaded");
    });

    // リップシンク用パラメータの収集
    // _lipSyncIds was never populated anywhere, so both halves of the lip-sync
    // block in Update() looped over zero ids: the speaking add did nothing, and
    // the "force mouth closed while not speaking" override — meant to mask idle
    // motions that carry mouth curves — did nothing either. hibiki was the
    // visible symptom: every one of her motions, Idle included, animates
    // PARAM_MOUTH_OPEN_Y, so she kept "talking" while idle.
    //
    // The model3.json LipSync group cannot be the source here: hibiki's json has
    // no such group at all (haru's does). Collect the model's own mouth-open
    // parameter ids instead — matched case- *and underscore-insensitively*:
    // hibiki/haru write PARAM_MOUTH_OPEN_Y, hiyori writes ParamMouthOpenY.
    for (csmInt32 i = 0; i < _model->GetParameterCount(); ++i)
    {
        if (ContainsIgnoreCase(_model->GetParameterId(i)->GetString().GetRawString(), "MOUTH_OPEN"))
        {
            _lipSyncIds.PushBack(_model->GetParameterId(i));
        }
    }

    // 表情データの読み込み
    for (auto expressionIndex = 0; expressionIndex < _modelJson->GetExpressionCount(); ++expressionIndex)
    {
        LoadAssets(_modelJson->GetExpressionFileName(expressionIndex), [=](Csm::csmByte* buffer, Csm::csmSizeInt bufferSize) {
            auto expressionName = _modelJson->GetExpressionName(expressionIndex);
            ACubismMotion* motion = LoadExpression(buffer, bufferSize, expressionName);

            if (motion)
            {
                if (_expressions[expressionName])
                {
                    ACubismMotion::Delete(_expressions[expressionName]);
                    _expressions[expressionName] = nullptr;
                }
                _expressions[expressionName] = motion;
            }
        });
    }

    //ポーズデータの読み込み
    LoadAssets(_modelJson->GetPoseFileName(), [=](Csm::csmByte* buffer, Csm::csmSizeInt bufferSize) {
        LoadPose(buffer, bufferSize);
    });

    // 物理演算データの読み込み
    LoadAssets(_modelJson->GetPhysicsFileName(), [=](Csm::csmByte* buffer, Csm::csmSizeInt bufferSize) {
        LoadPhysics(buffer, bufferSize);
    });

    // モデルに付属するユーザーデータの読み込み
    LoadAssets(_modelJson->GetUserDataFile(), [=](Csm::csmByte* buffer, Csm::csmSizeInt bufferSize) {
        LoadUserData(buffer, bufferSize);
    });

    // Layout
    csmMap<csmString, csmFloat32> layout;
    _modelJson->GetLayoutMap(layout);
    // レイアウト情報から位置を設定
    _modelMatrix->SetupFromLayout(layout);

    LAppPal::PrintLogLn("[DEBUG] Layout setup complete");

    // パラメータを保存
    _model->SaveParameters();

    // モーションデータの読み込み
    for (csmInt32 i = 0; i < _modelJson->GetMotionGroupCount(); i++)
    {
        const csmChar* group = _modelJson->GetMotionGroupName(i);
        // モーションデータをグループ名から一括でロードする
        PreloadMotionGroup(group);
    }

    _motionManager->StopAllMotions();

    // レンダラの作成
    CreateRenderer();

    // テクスチャのセットアップ
    SetupTextures();

    LAppPal::PrintLogLn("[DEBUG] SetupModel complete, textures loaded");

    _updating = false;
    _initialized = true;
}

void LAppMinimumModel::PreloadMotionGroup(const csmChar* group)
{
    const csmInt32 count = _modelJson->GetMotionCount(group);

    for (csmInt32 i = 0; i < count; i++)
    {
        //ex) idle_0
        csmString name = Utils::CubismString::GetFormatedString("%s_%d", group, i);
        csmString path = _modelJson->GetMotionFileName(group, i);
        path = csmString(_currentModelDirectory.c_str()) + path;

        if (_debugMode)
        {
            LAppPal::PrintLogLn("[APP]load motion: %s => [%s_%d] ", path.GetRawString(), group, i);
        }

        csmByte* buffer;
        csmSizeInt size;
        buffer = CreateBuffer(path.GetRawString(), &size);
        CubismMotion* tmpMotion = static_cast<CubismMotion*>(LoadMotion(buffer, size, name.GetRawString(), NULL, NULL, _modelJson, group, i));

        if (tmpMotion)
        {
            if (_motions[name] != NULL)
            {
                ACubismMotion::Delete(_motions[name]);
            }
            _motions[name] = tmpMotion;
        }

        DeleteBuffer(buffer, path.GetRawString());
    }

    CreateRenderer();

    SetupTextures();
}

void LAppMinimumModel::ReleaseMotionGroup(const csmChar* group) const
{
    const csmInt32 count = _modelJson->GetMotionCount(group);
    for (csmInt32 i = 0; i < count; i++)
    {
        csmString voice = _modelJson->GetMotionSoundFileName(group, i);
        if (strcmp(voice.GetRawString(), "") != 0)
        {
            csmString path = voice;
            path = csmString(_currentModelDirectory.c_str()) + path;
        }
    }
}

/**
* @brief すべてのモーションデータの解放
*
* すべてのモーションデータを解放する。
*/
void LAppMinimumModel::ReleaseMotions()
{
    for (csmMap<csmString, ACubismMotion*>::const_iterator iter = _motions.Begin(); iter != _motions.End(); ++iter)
    {
        ACubismMotion::Delete(iter->Second);
    }

    _motions.Clear();
}

/**
* @brief すべての表情データの解放
*
* すべての表情データを解放する。
*/
void LAppMinimumModel::ReleaseExpressions()
{
    for (csmMap<csmString, ACubismMotion*>::const_iterator iter = _expressions.Begin(); iter != _expressions.End(); ++iter)
    {
        ACubismMotion::Delete(iter->Second);
    }

    _expressions.Clear();
}

void LAppMinimumModel::Update()
{
    const csmFloat32 deltaTimeSeconds = LAppPal::GetDeltaTime();
    _userTimeSeconds += deltaTimeSeconds;

    _dragManager->Update(deltaTimeSeconds);
    _dragX = _dragManager->GetX();
    _dragY = _dragManager->GetY();

    // モーションによるパラメータ更新の有無
    csmBool motionUpdated = false;

    //-----------------------------------------------------------------
    _model->LoadParameters(); // 前回セーブされた状態をロード
    if (_motionManager->IsFinished())
    {
        // モーションの再生がない場合、待機モーションの中からランダムで再生する
        StartRandomMotion(LAppDefine::MotionGroupIdle, LAppDefine::PriorityIdle);
    }
    else
    {
        motionUpdated = _motionManager->UpdateMotion(_model, deltaTimeSeconds); // モーションを更新
    }
    _model->SaveParameters(); // 状態を保存
    //-----------------------------------------------------------------

    // まばたき
    if (!motionUpdated)
    {
        if (_eyeBlink)
        {
            // メインモーションの更新がないとき
            _eyeBlink->UpdateParameters(_model, deltaTimeSeconds); // 目パチ
        }
    }

    if (_expressionManager)
    {
        _expressionManager->UpdateMotion(_model, deltaTimeSeconds); // 表情でパラメータ更新（相対変化）
    }

    //ドラッグによる変化
    //ドラッグによる顔の向きの調整
    _model->AddParameterValue(_idParamAngleX, _dragX * 30); // -30から30の値を加える
    _model->AddParameterValue(_idParamAngleY, _dragY * 30);
    _model->AddParameterValue(_idParamAngleZ, _dragX * _dragY * -30);

    //ドラッグによる体の向きの調整
    _model->AddParameterValue(_idParamBodyAngleX, _dragX * 10); // -10から10の値を加える

    //ドラッグによる目の向きの調整
    _model->AddParameterValue(_idParamEyeBallX, _dragX); // -1から1の値を加える
    _model->AddParameterValue(_idParamEyeBallY, _dragY);

    // 呼吸など
    if (_breath)
    {
        _breath->UpdateParameters(_model, deltaTimeSeconds);
    }

    // 物理演算の設定
    if (_physics)
    {
        _physics->Evaluate(_model, deltaTimeSeconds);
    }

    // ポーズの設定
    if (_pose)
    {
        _pose->UpdateParameters(_model, deltaTimeSeconds);
    }

    // リップシンクの設定 - Use the stored lip-sync value
    // When not speaking (_lipSyncValue == 0), force mouth closed to override
    // idle motions that contain mouth animation curves (e.g. hibiki)
    if (_lipSyncValue > 0.0f)
    {
        for (csmUint32 i = 0; i < _lipSyncIds.GetSize(); ++i)
        {
            _model->AddParameterValue(_lipSyncIds[i], _lipSyncValue, 0.8f);
        }
    }
    else
    {
        for (csmUint32 i = 0; i < _lipSyncIds.GetSize(); ++i)
        {
            _model->SetParameterValue(_lipSyncIds[i], 0.0f);
        }
    }

    _model->Update();

}

CubismMotionQueueEntryHandle LAppMinimumModel::StartMotion(const csmChar* group, csmInt32 no, csmInt32 priority)
{
    // Bounds check: ensure motion index is valid for this group
    const csmInt32 motionCount = _modelJson->GetMotionCount(group);
    if (motionCount == 0)
    {
        if (_debugMode)
        {
            LAppPal::PrintLogLn("[APP]motion group [%s] has no motions, skipping.", group);
        }
        return InvalidMotionQueueEntryHandleValue;
    }
    if (no >= motionCount)
    {
        no = no % motionCount;
    }

    if (priority == PriorityForce)
    {
        _motionManager->SetReservePriority(priority);
    }
    else if (!_motionManager->ReserveMotion(priority))
    {
        if (_debugMode)
        {
            LAppPal::PrintLogLn("[APP]can't start motion.");
        }
        return InvalidMotionQueueEntryHandleValue;
    }

    const csmString fileName = _modelJson->GetMotionFileName(group, no);

    //ex) idle_0
    csmString name = Utils::CubismString::GetFormatedString("%s_%d", group, no);
    CubismMotion* motion = static_cast<CubismMotion*>(_motions[name.GetRawString()]);
    csmBool autoDelete = false;

    if (!motion)
    {
        csmString path = fileName;
        path = csmString(_currentModelDirectory.c_str()) + path;

        csmByte* buffer;
        csmSizeInt size;
        buffer = CreateBuffer(path.GetRawString(), &size);
        motion = static_cast<CubismMotion*>(LoadMotion(buffer, size, nullptr, NULL, NULL, _modelJson, group, no));

        if (motion)
        {
            autoDelete = true; // 終了時にメモリから削除
        }

        DeleteBuffer(buffer, path.GetRawString());
    }

    //voice
    csmString voice = _modelJson->GetMotionSoundFileName(group, no);
    if (strcmp(voice.GetRawString(), "") != 0)
    {
        csmString path = voice;
        path = csmString(_currentModelDirectory.c_str()) + path;
    }

    if (_debugMode)
    {
        LAppPal::PrintLogLn("[APP]start motion: [%s_%d]", group, no);
    }
    return  _motionManager->StartMotionPriority(motion, autoDelete, priority);
}

CubismMotionQueueEntryHandle LAppMinimumModel::StartRandomMotion(const csmChar* group, csmInt32 priority)
{
    if (_modelJson->GetMotionCount(group) == 0)
    {
        return InvalidMotionQueueEntryHandleValue;
    }

    csmInt32 no = rand() % _modelJson->GetMotionCount(group);

    return StartMotion(group, no, priority);
}

void LAppMinimumModel::Draw(CubismMatrix44& matrix)
{
    if (!_model)
    {
        return;
    }

    // DEBUG: Log draw calls
    static int drawCallCount = 0;
    if (drawCallCount < 3) {
        LAppPal::PrintLogLn("[DEBUG] Draw called, call=%d", drawCallCount);
        drawCallCount++;
    }

    matrix.MultiplyByMatrix(_modelMatrix);

    GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->SetMvpMatrix(&matrix);
    GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->DrawModel();
}

void LAppMinimumModel::SetExpression(const csmChar* expressionID)
{
    ACubismMotion* motion = _expressions[expressionID];
    if (_debugMode)
    {
        LAppPal::PrintLogLn("[APP]expression: [%s]", expressionID);
    }

    if (motion)
    {
        _expressionManager->StartMotion(motion, false);
    }
    else
    {
        if (_debugMode) LAppPal::PrintLogLn("[APP]expression[%s] is null ", expressionID);
    }
}

void LAppMinimumModel::ReloadRenderer()
{
    DeleteRenderer();

    CreateRenderer();

    SetupTextures();
}

void LAppMinimumModel::SetupTextures()
{
    LAppPal::PrintLogLn("[DEBUG] SetupTextures starting, texture count: %d", _modelJson->GetTextureCount());

    for (csmInt32 modelTextureNumber = 0; modelTextureNumber < _modelJson->GetTextureCount(); modelTextureNumber++)
    {
        // テクスチャ名が空文字だった場合はロード・バインド処理をスキップ
        if (strcmp(_modelJson->GetTextureFileName(modelTextureNumber), "") == 0)
        {
            continue;
        }

        //OpenGLのテクスチャユニットにテクスチャをロードする
        csmString texturePath = _modelJson->GetTextureFileName(modelTextureNumber);
        texturePath = csmString(_currentModelDirectory.c_str()) + texturePath;

        LAppPal::PrintLogLn("[DEBUG] Loading texture: %s", texturePath.GetRawString());

        LAppTextureManager::TextureInfo* texture = LAppMinimumDelegate::GetInstance()->GetTextureManager()->CreateTextureFromPngFile(texturePath.GetRawString());

        if (!texture) {
            LAppPal::PrintLogLn("[DEBUG] ERROR: Failed to load texture: %s", texturePath.GetRawString());
            continue;
        }

        const csmInt32 glTextueNumber = texture->id;
        LAppPal::PrintLogLn("[DEBUG] Texture loaded, GL id: %d", glTextueNumber);

        //OpenGL
        GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->BindTexture(modelTextureNumber, glTextueNumber);
    }

#ifdef PREMULTIPLIED_ALPHA_ENABLE
    GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->IsPremultipliedAlpha(true);
#else
    GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->IsPremultipliedAlpha(false);
#endif

    LAppPal::PrintLogLn("[DEBUG] SetupTextures complete");
}

void LAppMinimumModel::MotionEventFired(const csmString& eventValue)
{
    CubismLogInfo("%s is fired on LAppMinimumModel!!", eventValue.GetRawString());
}

Csm::Rendering::CubismOffscreenSurface_OpenGLES2 &LAppMinimumModel::GetRenderBuffer()
{
    return _renderBuffer;
}

void LAppMinimumModel::SetLipSyncValue(float value)
{
    _lipSyncValue = value;
}
