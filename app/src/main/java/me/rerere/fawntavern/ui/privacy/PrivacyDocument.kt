package me.rerere.fawntavern.ui.privacy

import me.rerere.fawntavern.R

enum class PrivacyDocument(val titleRes: Int, val assetFileName: String) {
    PRIVACY_POLICY(R.string.privacy_policy_title, "privacy_policy.html"),
    USER_AGREEMENT(R.string.user_agreement_title, "user_agreement.html"),
    PERSONAL_INFO_LIST(R.string.personal_info_list_title, "personal_info_list.html"),
    THIRD_PARTY_SHARING_LIST(R.string.third_party_sharing_list_title, "third_party_sharing_list.html"),
}
