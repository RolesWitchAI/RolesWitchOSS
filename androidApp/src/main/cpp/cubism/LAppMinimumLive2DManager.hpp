/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#pragma once

#include <CubismFramework.hpp>
#include <Math/CubismMatrix44.hpp>
#include <Type/csmVector.hpp>
#include <string>

class LAppMinimumModel;

/**
* @brief サンプルアプリケーションにおいてCubismModelを管理するクラス<br>
*         モデル生成と破棄、タップイベントの処理、モデル切り替えを行う。
*
*/
class LAppMinimumLive2DManager
{

public:
    /**
    * @brief   クラスのインスタンス（シングルトン）を返す。<br>
    *           インスタンスが生成されていない場合は内部でインスタンを生成する。
    *
    * @return  クラスのインスタンス
    */
    static LAppMinimumLive2DManager* GetInstance();

    /**
    * @brief   クラスのインスタンス（シングルトン）を解放する。
    *
    */
    static void ReleaseInstance();

    /**
    * @brief   現在のシーンで保持しているモデルを返す
    *
    * @return      モデルのインスタンスを返す。
    */
    LAppMinimumModel* GetModel() const;

    /**
    * @brief   現在のシーンで保持しているすべてのモデルを解放する
    *
    */
    void ReleaseModel();

    /**
    * @brief   画面をドラッグしたときの処理
    *
    * @param[in]   x   画面のX座標
    * @param[in]   y   画面のY座標
    */
    void OnDrag(Csm::csmFloat32 x, Csm::csmFloat32 y) const;

    /**
    * @brief   画面を更新するときの処理
    *          モデルの更新処理および描画処理を行う
    */
    void OnUpdate() const;

    /**
    * @brief Start a motion by group name and index
    */
    void StartMotion(const char* group, int no, int priority);

    /**
    * @brief Set lip-sync value for mouth animation (0.0 to 1.0)
    */
    void SetLipSyncValue(float value);

    /**
    * @brief Switch to a different model by index
    * @param[in] index Index in ModelDir array
    */
    void SwitchModel(int index);

    /**
    * @brief Get the current model index
    * @return Current model index
    */
    int GetModelIndex() const;

    /**
    * @brief Set the initial model index before first creation
    */
    static void SetInitialModelIndex(int index);

private:
    static int s_initialModelIndex;
    /**
    * @brief  コンストラクタ
    */
    LAppMinimumLive2DManager();

    /**
    * @brief  デストラクタ
    */
    virtual ~LAppMinimumLive2DManager();

    /**
    * @brief ディレクトリパスの設定
    *
    * モデルのディレクトリパスを設定する
    */
    void SetAssetDirectory(const std::string& path);

    /**
    * @brief モデルの読み込み
    *
    * モデルデータの読み込み処理を行う
    *
    * @param[in] modelDirectory モデルのディレクトリ名
    */
    void LoadModel(const std::string modelDirectoryName);

    Csm::CubismMatrix44*        _viewMatrix; ///< モデル描画に用いるView行列
    LAppMinimumModel*  _model; ///< モデルインスタンス
    int _currentModelIndex = 0; ///< Current model index in ModelDir array
    std::string _currentModelDirectory; ///< 現在のモデルのディレクトリ
};
