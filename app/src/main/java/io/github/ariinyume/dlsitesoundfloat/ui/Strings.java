/*
 * DLsiteSound Floating Subtitle - Xposed module for DLsite Sound
 * Copyright (C) 2026 ariinyume
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.ariinyume.dlsitesoundfloat.ui;

import java.util.Locale;

/**
 * 【M1】设置页三语文案表（PRD §FR-02：简体中文 / 繁體中文 / English）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 为什么写死在代码里、不用 res/values-* 资源
 *
 * PRD §FR-02 要求**在设置页内即时切换语言**（点一下按钮立刻全页换语言），
 * 而 {@code res/values-zh-rTW} 这类限定符资源要在 **Activity 重建**时才会重新解析，
 * 且与系统语言/per-app 语言强耦合 —— 做不到「页内秒切、且不影响其他 App」。
 * 本模块既有的对外文案（{@code util/I18n}）也是这个口径（见该类类头），保持一致。
 *
 * 参数占位统一用**位置参数**（{@code %1$s} / {@code %2$d}）：三种语言的语序不同，
 * 位置参数才能让译文自由调整词序。格式化一律走 {@link #format(UiLang, Object...)}
 * （内部用 {@link Locale#ROOT}，避免某些地区把数字格式化成 "1,5"）。
 * ─────────────────────────────────────────────────────────────────────
 */
public enum Strings {

    // ── 页面 ──────────────────────────────────────────────────────────
    PAGE_TITLE("可视化设置", "視覺化設定", "Visual settings"),
    SAVE("保存设置", "儲存設定", "Save settings"),
    RESET("重置", "重置", "Reset"),
    CANCEL("取消", "取消", "Cancel"),
    PROBING("状态检测中…", "狀態偵測中…", "Checking…"),

    // ── 状态卡（PRD §FR-01）────────────────────────────────────────────
    MODULE_ON("模块已激活", "模組已啟用", "Module active"),
    MODULE_OFF("模块未激活", "模組未啟用", "Module inactive"),
    /** STA-07：模块未激活时整页控件禁用 + 保存按钮不可用的提示。 */
    MODULE_INACTIVE_HINT("模块未启用，字幕调整功能无法使用", "模組未啟用，字幕調整功能無法使用",
            "Module inactive — subtitle settings unavailable"),
    MODULE_UNKNOWN("模块状态未知", "模組狀態未知", "Module state unknown"),
    /**
     * Q-6/E-10 判别态：SystemUI 有响应但 DLsiteSound 没响应 —— 前者证明模块已装好、
     * SystemUI 作用域已授权；后者只可能是「DLsiteSound 当前没在跑」（宿主不是常驻进程，
     * 开机后不启动就没有本模块代码在里面，PING 收不到 PONG）。**这不是未授权**，
     * 状态卡要单独成态、给出可执行的指引。
     */
    HOST_NOT_RUNNING("DLsiteSound 未运行", "DLsiteSound 未執行", "DLsiteSound not running"),
    HOST_NOT_RUNNING_HINT("请先启动 DLsiteSound 应用（进入任意作品即可）；已保存的设置会在它启动后自动生效",
            "請先啟動 DLsiteSound 應用（進入任意作品即可）；已儲存的設定會在它啟動後自動生效",
            "Open the DLsiteSound app once; saved settings apply automatically once it starts"),
    SYSTEMUI_ON("SystemUI 已授权", "SystemUI 已授權", "SystemUI authorized"),
    SYSTEMUI_OFF("SystemUI 未授权", "SystemUI 未授權", "SystemUI not authorized"),
    SYSTEMUI_UNKNOWN("SystemUI 状态未知", "SystemUI 狀態未知", "SystemUI state unknown"),
    /** 副标题格式：{@code v{版本名} (code {版本号}) · {SystemUI 授权状态}}。 */
    STATUS_SUBTITLE("v%1$s (code %2$d) · %3$s", "v%1$s (code %2$d) · %3$s", "v%1$s (code %2$d) · %3$s"),
    /**
     * PRD §12 E-01：**两侧都没响应**时的引导文案（最可能是作用域没勾）。
     *
     * ⚠️ 比 PRD 原文多一句「若已勾选，请先启动一次 DLsiteSound 再回到本页」：
     *    本实现的作用域检测是**进程间握手**（见 {@link io.github.ariinyume.dlsitesoundfloat.util.ScopeProbe}），
     *    而 DLsiteSound 没在跑时那个进程里根本没有本模块的代码 → 收不到应答。
     *    也就是说「已授权但目标 App 未运行」与「未授权」在这里是同一个观测结果，
     *    必须把第二种可能也告诉用户，否则会让人以为勾选丢了。
     */
    GUIDE_OFF("请在 LSPosed 中勾选 DLsiteSound 作用域后重启系统界面；若已勾选，请先启动一次 DLsiteSound 再回到本页",
            "請在 LSPosed 中勾選 DLsiteSound 作用域後重啟系統介面；若已勾選，請先啟動一次 DLsiteSound 再回到本頁",
            "Enable the DLsiteSound scope in LSPosed, then restart SystemUI. "
                    + "If it is already enabled, open DLsiteSound once and come back to this page"),
    /**
     * PRD §12 E-10：**SystemUI 有响应、DLsiteSound 没响应**时的引导文案。
     *
     * 这一态是可判别的、而且比「全都没响应」更有信息量：SystemUI 能应答说明模块本身装好了、
     * 至少一个作用域生效了，所以问题几乎只可能落在「DLsiteSound 作用域没勾」或
     * 「DLsiteSound 当前没运行」这两件事上，文案就直接指这两条，不要再把用户赶去翻全局配置。
     */
    GUIDE_UNKNOWN("未探测到 DLsiteSound 进程响应，请先启动一次 DLsiteSound；若仍无效，请在 LSPosed 中确认已勾选它并重启系统界面",
            "未偵測到 DLsiteSound 程序回應，請先啟動一次 DLsiteSound；若仍無效，請在 LSPosed 中確認已勾選它並重啟系統介面",
            "No response from the DLsiteSound process. Open DLsiteSound once; if it still fails, "
                    + "make sure its scope is enabled in LSPosed and restart SystemUI"),
    /** PRD §12 E-02：SystemUI 未授权时重启按钮旁的提示（M2 的重启按钮会用到，先备好）。 */
    GUIDE_SYSTEMUI_OFF("重启前请先在 LSPosed 勾选 SystemUI 作用域",
            "重啟前請先在 LSPosed 勾選 SystemUI 作用域",
            "Enable the SystemUI scope in LSPosed before restarting"),

    // ── 语言（PRD §FR-01 规则 6 / §FR-02）──────────────────────────────
    LANGUAGE("语言", "語言", "Language"),
    LANG_DIALOG_TITLE("选择语言", "選擇語言", "Choose language"),
    /** 语言选择弹层里的一项：语言用「它自己的语言」写（简体中文 / 繁體中文 / English）。 */
    LANG_NATIVE_ZH_CN("简体中文", "简体中文", "简体中文"),
    LANG_NATIVE_ZH_TW("繁體中文", "繁體中文", "繁體中文"),
    LANG_NATIVE_EN("English", "English", "English"),
    FOLLOW_SYSTEM("跟随系统", "跟隨系統", "Follow system"),

    // ── 分组标题 ──────────────────────────────────────────────────────
    /** 页头大标题下的副标题（参考稿：DLsiteSound 悬浮窗 · 状态栏字幕外观调整）。 */
    HEADER_SUBTITLE("DLsiteSound 悬浮窗 · 状态栏字幕外观调整",
            "DLsiteSound 懸浮窗 · 狀態欄字幕外觀調整",
            "DLsiteSound floating window · status bar subtitle appearance"),
    /** 参考稿的「调整预览窗」节标题（预览卡：示例字幕随草稿参数实时变化）。 */
    PREVIEW_TITLE("调整预览窗", "調整預覽窗", "Preview"),
    GROUP_MAIN("主字幕 / 活动行调整", "主字幕 / 活動行調整", "Main subtitle / active line"),
    GROUP_INACTIVE("非活动行字幕调整", "非活動行字幕調整", "Inactive lines"),
    GROUP_TYPO("字幕排版", "字幕排版", "Typography"),
    GROUP_MISC("其他", "其他", "More"),

    // ── 其他（PRD §FR-07 / 备份恢复 / 常亮）────────────────────────────
    /**
     * v1.4 §2.1.1.4：本开关是**功能级总闸**（原「状态栏字幕」→「状态栏字幕功能」）。
     *
     * ⚠️ 语义边界（与宿主侧会话级开关**不是**同一个东西）：
     *    本项 = 插件级能力开关，关闭 ⇒ 状态栏字幕停显 + 播放页那个开关按钮从界面上消失；
     *    播放页开关 = 用户在当前播放会话里的临时开关，存在宿主侧，**不写回这个配置键**。
     *    两级关系见 {@code SubtitleConfig#statusbarSubtitleEnabled} 的注释。
     */
    STATUSBAR_SUBTITLE("状态栏字幕功能", "狀態欄字幕功能", "Status bar subtitle"),
    /**
     * v1.4 §2.1.1.4：关闭「状态栏字幕功能」时弹出的长提示。
     *
     * 必须说清「本开关撤销不了已经给出去的 SystemUI 权限」—— LSPosed 的作用域授权是
     * 用户授予插件进程的，插件自身无法收回；用户只能去 Xposed 管理器里手动取消。
     * 不写这一句会被理解成「关掉开关就等于把权限还回去了」，与事实不符。
     */
    STATUSBAR_REVOKE_HINT("因功能限制，本开关无法直接撤销已赋予插件的 SystemUI 权限，请在 Xposed 插件管理器中自行撤销",
            "因功能限制，本開關無法直接撤銷已賦予外掛的 SystemUI 權限，請在 Xposed 外掛管理員中自行撤銷",
            "Due to a platform limitation, this switch cannot revoke the SystemUI permission already granted to the module. "
                    + "Please revoke it manually in your Xposed module manager"),
    BACKUP_RESTORE("字幕配置备份与恢复", "字幕配置備份與恢復", "Backup & restore"),
    EXPORT("导出", "匯出", "Export"),
    IMPORT("导入", "匯入", "Import"),
    RESTART_SYSUI("重启系统界面", "重啟系統介面", "Restart SystemUI"),
    RESTART_CONFIRM_TITLE("重启系统界面？", "重啟系統介面？", "Restart SystemUI?"),
    RESTART_CONFIRM_MSG("状态栏字幕会随 SystemUI 重启而生效/刷新，重启约需几秒钟。",
            "狀態欄字幕會隨 SystemUI 重啟而生效/重新整理，重啟約需幾秒鐘。",
            "Status bar subtitle settings take effect when SystemUI restarts (a few seconds)."),
    EXPORT_DONE("已导出", "已匯出", "Exported"),
    EXPORT_FAILED("导出失败：%1$s", "匯出失敗：%1$s", "Export failed: %1$s"),
    IMPORT_DONE("已导入并应用", "已匯入並套用", "Imported and applied"),
    /** 兜底文案（原因分类见 {@link #IMPORT_FAILED_NOT_JSON} 等三条专项文案）。 */
    IMPORT_FAILED("导入失败：%1$s", "匯入失敗：%1$s", "Import failed: %1$s"),
    /**
     * v1.4 §5.1.1：导入失败按**内容形态**分三档，让用户知道是「选错文件」还是「文件本身坏了」。
     * 这三条已自带完整前缀，用 {@link #get(UiLang)} 直接取，不要再套 {@link #IMPORT_FAILED}。
     */
    IMPORT_FAILED_NOT_JSON("导入失败：非字幕配置文件", "匯入失敗：非字幕設定檔", "Import failed: not a subtitle config file"),
    IMPORT_FAILED_EMPTY("导入失败：字幕配置文件为空", "匯入失敗：字幕設定檔為空", "Import failed: the subtitle config file is empty"),
    IMPORT_FAILED_BROKEN("导入失败：非字幕配置文件或文件内容损坏",
            "匯入失敗：非字幕設定檔或檔案內容損毀",
            "Import failed: not a subtitle config file, or the file is corrupted"),
    IMPORT_TOO_NEW("配置文件版本过新，请升级模块后再导入", "設定檔版本過新，請升級模組後再匯入",
            "Config schema is newer than this build — please upgrade the module first"),

    // ── 主字幕 / 活动行（PRD §FR-04）───────────────────────────────────
    SUBTITLE_COLOR("主字幕颜色", "主字幕顏色", "Subtitle color"),
    SHADOW_COLOR("阴影颜色", "陰影顏色", "Shadow color"),
    SHADOW_STRENGTH("阴影强度", "陰影強度", "Shadow strength"),
    SHADOW_RADIUS("阴影半径", "陰影半徑", "Shadow radius"),
    /** 语义 = 活动行不透明度（50–100%，拍板 Q-4）。 */
    ACTIVE_HIGHLIGHT("活动行不透明度", "活動行不透明度", "Active line opacity"),
    ACTIVE_SCALE("主字幕放大倍数", "主字幕放大倍數", "Active line scale"),
    RESET_MAIN("恢复主字幕默认设置", "恢復主字幕預設設定", "Reset main subtitle defaults"),
    /**
     * 固定文案（拍板 MSU-08）：展示默认值，不随上方设置变化。
     *
     * ⚠️ v1.4 §16.3 已**删除「字重」整行**，故本小字不再提字重（否则留下 dangling 引用，
     * 用户会去找一个界面上根本不存在的控件）。阴影强度默认值同时由 80% 回调为 50%。
     */
    HINT_MAIN("主字幕颜色 白色、阴影颜色 黑色、阴影强度 50%、阴影半径 10%、不透明度 100%、放大倍数 ×1.0",
            "主字幕顏色 白色、陰影顏色 黑色、陰影強度 50%、陰影半徑 10%、不透明度 100%、放大倍數 ×1.0",
            "Color White · Shadow Black · Strength 50% · Radius 10% · Opacity 100% · Scale ×1.0"),

    // ── 非活动行（PRD §FR-05）─────────────────────────────────────────
    BLUR_ENABLED("模糊非活动行", "模糊非活動行", "Blur inactive lines"),
    /** 滑块刻度是「×2 档」（1 档 = 0.5px），读数用 {@link #VALUE_PX} 直接显示 px。 */
    BLUR_RADIUS("模糊半径", "模糊半徑", "Blur radius"),
    INACTIVE_SCALE_ENABLED("缩放非活动行", "縮放非活動行", "Scale inactive lines"),
    INACTIVE_SCALE_PCT("缩放比例", "縮放比例", "Scale ratio"),
    RESET_INACTIVE("恢复非活动行字幕默认设置", "恢復非活動行字幕預設設定", "Reset inactive line defaults"),
    HINT_INACTIVE("模糊非活动行 开启、模糊半径 3.0px、缩放非活动行 开启、缩放比例 90%",
            "模糊非活動行 開啟、模糊半徑 3.0px、縮放非活動行 開啟、縮放比例 90%",
            "Blur On · Radius 3.0px · Scale On · Ratio 90%"),
    ON("开启", "開啟", "On"),
    OFF("关闭", "關閉", "Off"),

    // ── 排版（PRD §FR-06）─────────────────────────────────────────────
    ALIGN("对齐", "對齊", "Alignment"),
    ALIGN_LEFT("左对齐", "靠左", "Left"),
    ALIGN_CENTER("居中", "置中", "Center"),
    ALIGN_RIGHT("右对齐", "靠右", "Right"),
    LINE_SPACING("字幕间行距", "字幕間行距", "Line spacing"),
    WRAP_SPACING("长字幕内换行额外行距", "長字幕內換行額外行距", "Wrap extra spacing"),
    RESET_TYPO("恢复字幕排版默认设置", "恢復字幕排版預設設定", "Reset typography defaults"),
    /**
     * v1.4 §16.3：已删除「字重」整行，小字同步去掉（不再有 dangling 引用）。
     *
     * ⚠️ 悬浮窗颜色这一段按 PRD §16.6 第 5 行 + 验收 N-09 的原文写成**「悬浮窗颜色 黑色」**
     * （三语分别是「悬浮窗颜色 黑色」/「懸浮窗顏色 黑色」/「Floating window color Black」），
     * **不要写色值** `#0E1420`：用户看的是「默认是什么颜色」，不是十六进制码，
     * 而且默认色如果将来调整，色值文案会变成一份需要手工同步的第二真相。
     */
    HINT_TYPO("对齐 居中、字幕间行距 0 dp、长字幕内换行额外行距 0 dp、悬浮窗颜色 黑色",
            "對齊 置中、字幕間行距 0 dp、長字幕內換行額外行距 0 dp、懸浮窗顏色 黑色",
            "Align Center · Line spacing 0 dp · Wrap extra 0 dp · Floating window color Black"),
    /** v1.4 §3.1.5：新增「悬浮窗颜色」行的标签（摆在换行额外行距下方）。 */
    FLOAT_WINDOW_COLOR("悬浮窗颜色", "懸浮窗顏色", "Panel color"),

    // ── 数值格式 ──────────────────────────────────────────────────────
    /** 百分比（0–100 的整数）。 */
    VALUE_PCT("%1$d%%", "%1$d%%", "%1$d%%"),
    /** 放大倍数（×1.0 / ×1.5）。 */
    VALUE_SCALE("×%1$s", "×%1$s", "×%1$s"),
    /**
     * 模糊半径读数（v1.4 §2.1.1：**直接显示 px**，不再显示「8 × 0.5px」这种档位算式）。
     * 滑块内部仍走 0–16 的档位刻度（1 档 = 0.5px），换算在 SettingsActivity 里做。
     */
    VALUE_PX("%1$spx", "%1$spx", "%1$spx"),
    /** dp 值（{@code -5 dp} / {@code 2.5 dp}）。 */
    VALUE_DP("%1$s dp", "%1$s dp", "%1$s dp"),

    // ── 提示与报错（PRD §9.1 / §12）───────────────────────────────────
    SAVE_OK("已保存", "已儲存", "Saved"),
    SAVE_NO_CHANGE("配置无变化", "設定無變更", "No changes"),
    SAVE_FAILED("保存失败：%1$s", "儲存失敗：%1$s", "Save failed: %1$s"),
    RESET_DONE("已恢复默认，记得保存", "已恢復預設，記得儲存", "Defaults restored — remember to save"),
    LOAD_RESET("原配置文件已损坏，已重置为默认值", "原設定檔已損毀，已重設為預設值",
            "The config file was corrupted and has been reset to defaults"),
    /** PRD §12 E-05：未保存退出的二次确认（三选：保存 / 不保存 / 取消）。 */
    UNSAVED_TITLE("有未保存的更改", "有未儲存的變更", "Unsaved changes"),
    UNSAVED_MSG("是否保存对字幕外观的修改？", "是否儲存對字幕外觀的修改？", "Save your subtitle appearance changes?"),
    DISCARD("不保存", "不儲存", "Discard"),

    // ── 色板色名（PRD §八 NFR-05：色板不能只靠颜色区分，必须附色名）────
    COLOR_WHITE("白", "白", "White"),
    COLOR_BLACK("黑", "黑", "Black"),
    COLOR_GREEN("绿", "綠", "Green"),
    COLOR_RED("红", "紅", "Red"),
    COLOR_ORANGE("橙", "橙", "Orange"),
    COLOR_BLUE("蓝", "藍", "Blue"),
    COLOR_PURPLE("紫", "紫", "Purple"),
    COLOR_CYAN("青", "青", "Cyan"),
    /**
     * v1.4 §3.1.5：悬浮窗色板的第一个预设是「深蓝黑」（默认基色 {@code #0E1420}），
     * 它与「黑」在视觉上极接近但不相等，必须单独给色名，否则 NFR-05 的
     * 「色板不能只靠颜色区分」在无障碍朗读里会露出「黑」和「黑」两个一样的项。
     */
    COLOR_DEEP_BLUE_BLACK("深蓝黑", "深藍黑", "Deep navy"),
    /** 色板末尾的「自定义取色」圆形图标按钮与弹窗标题（参考稿的 🎨 位置）。 */
    COLOR_CUSTOM("自定义颜色", "自訂顏色", "Custom color"),
    /** v1.5 §17.3（2.3.1 返工）：自定义取色改为「色板 + 十六进制输入」，不再用 RGB 滑杆。 */
    COLOR_HEX_INPUT("十六进制颜色值", "十六進位色值", "Hex color"),
    COLOR_HEX_INVALID("请输入合法的颜色值，例如 #FF8A65", "請輸入合法的色值，例如 #FF8A65",
            "Enter a valid color, e.g. #FF8A65"),
    COLOR_PREVIEW_LABEL("预览", "預覽", "Preview"),
    OK("确定", "確定", "OK");

    private final String zh;
    private final String tw;
    private final String en;

    Strings(String zh, String tw, String en) {
        this.zh = zh;
        this.tw = tw;
        this.en = en;
    }

    /** 取指定界面语言下的文案（{@link UiLang#SYSTEM} 会先解析成具体语言）。 */
    public String get(UiLang lang) {
        switch (lang == null ? UiLang.ZH_CN : lang.resolve()) {
            case ZH_TW:
                return tw;
            case EN:
                return en;
            case ZH_CN:
            default:
                return zh;
        }
    }

    /** 取文案并按位置参数格式化（占位符见各枚举项注释）。 */
    public String format(UiLang lang, Object... args) {
        String tpl = get(lang);
        if (args == null || args.length == 0) {
            return tpl;
        }
        try {
            return String.format(Locale.ROOT, tpl, args);
        } catch (Throwable t) {
            return tpl;
        }
    }
}
