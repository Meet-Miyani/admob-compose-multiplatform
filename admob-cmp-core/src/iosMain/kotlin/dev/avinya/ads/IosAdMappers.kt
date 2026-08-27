@file:OptIn(ExperimentalForeignApi::class)

package dev.avinya.ads

import GoogleMobileAds.GADAdValue
import GoogleMobileAds.GADAdValuePrecision
import GoogleMobileAds.GADAdValuePrecisionEstimated
import GoogleMobileAds.GADAdValuePrecisionPrecise
import GoogleMobileAds.GADAdValuePrecisionPublisherProvided
import GoogleMobileAds.GADAdValuePrecisionUnknown
import GoogleMobileAds.GADAgeRestrictedTreatmentChild
import GoogleMobileAds.GADAgeRestrictedTreatmentTeen
import GoogleMobileAds.GADAgeRestrictedTreatmentUnspecified
import GoogleMobileAds.GADAdNetworkResponseInfo
import GoogleMobileAds.GADErrorUserInfoKeyResponseInfo
import GoogleMobileAds.GADExtras
import GoogleMobileAds.GADMaxAdContentRatingGeneral
import GoogleMobileAds.GADMaxAdContentRatingMatureAudience
import GoogleMobileAds.GADMaxAdContentRatingParentalGuidance
import GoogleMobileAds.GADMaxAdContentRatingTeen
import GoogleMobileAds.GADMobileAds
import GoogleMobileAds.GADRequest
import GoogleMobileAds.GADRequestConfiguration
import GoogleMobileAds.GAMRequest
import GoogleMobileAds.GADPublisherPrivacyPersonalizationStateEnabled
import GoogleMobileAds.GADPublisherPrivacyPersonalizationStateDisabled
import GoogleMobileAds.GADResponseInfo
import GoogleMobileAds.GADServerSideVerificationOptions
import kotlinx.cinterop.useContents
import platform.Foundation.NSDecimalNumber
import platform.Foundation.NSDecimalNumberHandler
import platform.Foundation.NSRoundingMode
import platform.Foundation.NSError
import kotlinx.cinterop.ExperimentalForeignApi

internal fun AdRequestOptions.toGADRequest(): GADRequest {
    val needsAdManager = publisherProvidedId != null || categoryExclusions.isNotEmpty()
    val request = if (needsAdManager) GAMRequest() else GADRequest()
    if (keywords.isNotEmpty()) request.keywords = keywords.toList()
    request.contentURL = contentUrl
    if (neighboringContentUrls.isNotEmpty()) request.neighboringContentURLStrings = neighboringContentUrls.toList()
    requestAgent?.let { request.requestAgent = it }
    placementId?.let { request.placementID = it }
    if (customTargeting.isNotEmpty()) {
        // GADRequest.customTargeting is a flat dictionary; mirror the Android mapping by
        // using the single value when there is one, otherwise the comma-joined list.
        request.customTargeting = customTargeting.mapValues { (_, values) ->
            if (values.size == 1) values.first() else values.joinToString(",")
        } as Map<Any?, *>
    }
    if (googleExtras.isNotEmpty()) {
        val extras = GADExtras()
        extras.additionalParameters = googleExtras as Map<Any?, *>
        request.registerAdNetworkExtras(extras)
    }
    if (request is GAMRequest) {
        publisherProvidedId?.let { request.publisherProvidedID = it }
        if (categoryExclusions.isNotEmpty()) {
            request.categoryExclusions = categoryExclusions.toList()
        }
    }
    // skipUninitializedAdapters remains Android-only; iOS GMA initializes adapters globally at SDK start.
    return request
}

internal fun GlobalRequestConfiguration.applyTo(requestConfiguration: GADRequestConfiguration) {
    if (testDeviceIds.isNotEmpty()) requestConfiguration.testDeviceIdentifiers = testDeviceIds
    when (maxAdContentRating) {
        MaxAdContentRating.General -> requestConfiguration.maxAdContentRating = GADMaxAdContentRatingGeneral
        MaxAdContentRating.ParentalGuidance -> requestConfiguration.maxAdContentRating = GADMaxAdContentRatingParentalGuidance
        MaxAdContentRating.Teen -> requestConfiguration.maxAdContentRating = GADMaxAdContentRatingTeen
        MaxAdContentRating.MatureAudience -> requestConfiguration.maxAdContentRating = GADMaxAdContentRatingMatureAudience
        MaxAdContentRating.Unspecified -> Unit
    }
    requestConfiguration.ageRestrictedTreatment = when (ageRestrictedTreatment) {
        AgeRestrictedTreatment.Unspecified -> GADAgeRestrictedTreatmentUnspecified
        AgeRestrictedTreatment.Child -> GADAgeRestrictedTreatmentChild
        AgeRestrictedTreatment.Teen -> GADAgeRestrictedTreatmentTeen
    }
    when (publisherPrivacyPersonalizationState) {
        PublisherPrivacyPersonalizationState.Enabled -> requestConfiguration.publisherPrivacyPersonalizationState = GADPublisherPrivacyPersonalizationStateEnabled
        PublisherPrivacyPersonalizationState.Disabled -> requestConfiguration.publisherPrivacyPersonalizationState = GADPublisherPrivacyPersonalizationStateDisabled
        PublisherPrivacyPersonalizationState.Default -> Unit
    }
    appMuted?.let { GADMobileAds.sharedInstance.applicationMuted = it }
    appVolume?.let { GADMobileAds.sharedInstance.applicationVolume = it.coerceIn(0f, 1f) }
}

internal fun NSError.toAdError(): AdError {
    val responseInfo = userInfo[GADErrorUserInfoKeyResponseInfo] as? GADResponseInfo
    return AdError(
        code = code.toString(),
        message = localizedDescription,
        domain = domain,
        responseInfo = responseInfo?.toCommon()
    )
}

internal fun GADResponseInfo.toCommon(): AdResponseInfo = AdResponseInfo(
    responseId = responseIdentifier,
    adapterClassName = loadedAdNetworkResponseInfo?.adNetworkClassName,
    // P1-15: iOS returned an empty map here while Android mapped responseExtras, so
    // mediation troubleshooting and experiment attribution silently lost data on one
    // platform only. GADResponseInfo.extrasDictionary is the documented equivalent
    // ("extra parameters that may be returned in an ad response").
    extras = extrasDictionary.toStringMap(),
    loadedAdNetworkResponseInfo = loadedAdNetworkResponseInfo?.toCommon(),
    adNetworkResponseInfos = adNetworkInfoArray.mapNotNull { (it as? GADAdNetworkResponseInfo)?.toCommon() }
)

/**
 * Flattens an ObjC `NSDictionary<NSString *, id>` to the common `Map<String, String>`.
 *
 * Values are `id`, so anything non-string is rendered with `toString()` rather than dropped —
 * losing a key entirely would reproduce the very gap this fixes.
 */
private fun Map<Any?, *>.toStringMap(): Map<String, String> =
    entries.mapNotNull { (key, value) ->
        val name = key as? String ?: return@mapNotNull null
        name to (value?.toString() ?: "")
    }.toMap()

private fun GADAdNetworkResponseInfo.toCommon(): AdNetworkResponseInfo = AdNetworkResponseInfo(
    adapterClassName = adNetworkClassName,
    latencyMillis = (latency * 1000.0).toLong(),
    error = error?.toAdError(),
    adSourceName = adSourceName,
    adSourceId = adSourceID,
    adSourceInstanceName = adSourceInstanceName,
    adSourceInstanceId = adSourceInstanceID
)

/**
 * Converts a GMA decimal ad value to exact micros.
 *
 * P1-14: this used to be `doubleValue * 1_000_000` truncated to Long. Values whose exact
 * decimal is not representable in binary floating point — 0.07 and 8.87 are the textbook
 * cases — drifted by one or more micros, so exported revenue no longer matched the source
 * SDK. All arithmetic now stays in `NSDecimalNumber`, which is base-10.
 *
 * Rounding is explicit: half-up at the micro, applied only below micro precision.
 */
internal fun NSDecimalNumber.toValueMicros(): Long =
    decimalNumberByMultiplyingByPowerOf10(6)
        .decimalNumberByRoundingAccordingToBehavior(
            NSDecimalNumberHandler.decimalNumberHandlerWithRoundingMode(
                roundingMode = NSRoundingMode.NSRoundPlain,
                scale = 0,
                raiseOnExactness = false,
                raiseOnOverflow = false,
                raiseOnUnderflow = false,
                raiseOnDivideByZero = false
            )
        )
        .longLongValue

internal fun GADAdValue.toCommon(): AdValue = AdValue(
    valueMicros = value.toValueMicros(),
    currencyCode = currencyCode,
    precision = precision.toCommon()
)

private fun GADAdValuePrecision.toCommon(): AdValuePrecision = when (this) {
    GADAdValuePrecisionUnknown -> AdValuePrecision.Unknown
    GADAdValuePrecisionEstimated -> AdValuePrecision.Estimated
    GADAdValuePrecisionPublisherProvided -> AdValuePrecision.PublisherProvided
    GADAdValuePrecisionPrecise -> AdValuePrecision.Precise
    else -> AdValuePrecision.Unknown
}

internal fun FullScreenAdOptions.serverSideVerificationOptions(): GADServerSideVerificationOptions? {
    val ssv = serverSideVerification ?: return null
    val options = GADServerSideVerificationOptions()
    ssv.userId?.let { options.userIdentifier = it }
    ssv.customData?.let { options.customRewardString = it }
    return options
}
