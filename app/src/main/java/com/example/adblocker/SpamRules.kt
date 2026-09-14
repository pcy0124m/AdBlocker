package com.example.adblocker

/**
 * 内置骚扰规则库（开箱即用，无需手动加黑名单即可自动拦一部分）。
 *
 * 设计原则：宁可漏拦也不要误拦。
 *   - 电话：只拦「高确信」的境外 / 虚拟运营商转售号段（诈骗、营销高发）。
 *            境内正常号（13x/15x/18x/19x）一律放行，避免误伤亲友来电。
 *   - 短信：以「高确信垃圾关键词」为主，「严格境外号段」为辅；
 *            不拦 106 等正规通知号段，避免误拦验证码 / 快递 / 账单。
 *
 * 与手动黑名单（BlockListManager）是「并集」关系：命中任一即拦。
 */
object SpamRules {

    /**
     * 电话高危号段前缀。
     * 170/171/165/167/162/174/175/176 为虚拟运营商转售号段，诈骗/营销高发；
     * 140-149 中多为早期物联/转售号段。境内正常手机号（11 位）绝不会命中这些前缀。
     */
    private val CALL_PREFIXES = setOf(
        "170", "171", "165", "167", "162", "174", "175", "176",
        "140", "141", "144", "146", "147", "148", "149"
    )

    /** 短信严格号段（仅真正境外：00 / + 且排除中国区号 0086/+86）。 */
    private val SMS_OVERSEAS_PREFIXES = setOf("00", "+")

    /**
     * 短信高确信垃圾关键词（命中即拦）。
     * 只收「诈骗 / 营销 / 违法」特征强、正常通知（验证码、快递、银行账单）不会出现的词。
     */
    private val SMS_KEYWORDS = setOf(
        "中奖", "返利", "免费领取", "点击链接", "退订回T", "退订回t", "回TD", "回T退订",
        "TD退订", "回复TD",
        "贷款", "借钱", "网贷", "借款", "额度", "低息", "免息", "高利",
        "博彩", "彩票", "赌球", "赌博", "下注",
        "代办", "代开发票", "兼职", "刷单", "返佣", "佣金",
        "加微信", "退保", "理赔", "色情", "约炮", "成人", "同城交友", "裸聊",
        "荐股", "炒股", "牛股", "内部消息", "稳赚",
        "快递丢失", "包裹丢失", "快递赔付", "订单异常", "理赔退款"
    )

    private fun normalize(n: String): String = n.replace("[^0-9+]".toRegex(), "")

    /** 来电是否命中骚扰号段（已排除带中国区号的境内号码）。 */
    fun isSpamCall(raw: String?): Boolean {
        val n = raw?.let { normalize(it) } ?: return false
        // 带 +86 / 0086 的境内号码不能拦
        if (n.startsWith("0086") || n.startsWith("+86")) return false
        // 其余 00 / + 开头视为境外来电（诈骗高发）
        if (n.startsWith("00") || n.startsWith("+")) return true
        return CALL_PREFIXES.any { n.startsWith(it) }
    }

    /** 短信是否命中垃圾规则（境外号段或高确信关键词）。 */
    fun isSpamSms(rawNumber: String?, body: String): Boolean {
        val n = rawNumber?.let { normalize(it) } ?: ""
        val overseas = (n.startsWith("00") || n.startsWith("+")) &&
            !n.startsWith("0086") && !n.startsWith("+86")
        val kwHit = body.isNotBlank() && SMS_KEYWORDS.any { body.contains(it) }
        return overseas || kwHit
    }
}
