package com.google.android.ump; import android.app.Activity;
public interface ConsentInformation { enum PrivacyOptionsRequirementStatus { UNKNOWN, NOT_REQUIRED, REQUIRED }
 interface OnConsentInfoUpdateSuccessListener { void onConsentInfoUpdateSuccess(); } interface OnConsentInfoUpdateFailureListener { void onConsentInfoUpdateFailure(FormError e); }
 boolean canRequestAds(); PrivacyOptionsRequirementStatus getPrivacyOptionsRequirementStatus();
 void requestConsentInfoUpdate(Activity a, ConsentRequestParameters p, OnConsentInfoUpdateSuccessListener s, OnConsentInfoUpdateFailureListener f); }
