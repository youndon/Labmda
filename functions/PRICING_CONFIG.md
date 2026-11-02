# Phase 02 pricing configuration

The pricing engine intentionally has **no hard-coded production rates**. A quote fails closed until an administrator provisions a valid object at the Realtime Database path `pricing/config`.

All monetary values ending in `Minor` are integer PHP centavos (₱1.00 = 100). Commission and provider percentages are basis points (100 = 1%; 1,000 = 10%).

## Required configuration shape

```json
{
  "version": 1,
  "currency": "PHP",
  "ride": {
    "baseFareMinor": 0,
    "perKmMinor": 0,
    "perMinuteMinor": 0,
    "minimumFareMinor": 0,
    "platformCommissionBps": 0
  },
  "delivery": {
    "baseFeeMinor": 0,
    "perKmMinor": 0,
    "minimumFeeMinor": 0,
    "platformCommissionBps": 0
  },
  "food": { "commissionBps": 0 },
  "shop": { "commissionBps": 0 },
  "providerFees": {
    "GCASH": {
      "rateBps": 0,
      "fixedFeeMinor": 0,
      "payer": "PLATFORM"
    }
  }
}
```

The zeroes above are schema placeholders, **not recommended or deployable prices**. Replace them with approved commercial rates before enabling quotes. Increment `version` whenever any rate or fee policy changes.

## Discount rules

Store discount records at `pricing/discounts/{UPPERCASE_CODE}`:

- `active`: boolean
- `kind`: `FIXED` or `PERCENT`
- `valueMinor`: fixed discount in centavos, for FIXED
- `valueBps`: discount percentage in basis points, for PERCENT
- `maxDiscountMinor`: optional cap in centavos
- `minimumSubtotalMinor`: optional minimum basket subtotal
- `funder`: `PLATFORM` or `MERCHANT`
- `startsAt`, `endsAt`: optional Unix epoch milliseconds

A merchant-funded discount reduces the merchant's commission base and earnings. A platform-funded discount is borne by the platform and does not reduce the merchant's commission base.

## Quotation functions

- `quoteRide`
- `quoteFoodOrder`
- `quoteShopOrder`
- `quoteDelivery`

All require an authenticated Firebase user and App Check. Route distance and duration are fetched server-side from Google Routes API using `GOOGLE_MAPS_ROUTES_API_KEY`; client-supplied distance is never used. Quotes are stored at `pricing/quotes/{quoteId}` and expire after five minutes.

Before deployment:

1. Enable Google Routes API in the Google Cloud project.
2. Set the secret: `firebase functions:secrets:set GOOGLE_MAPS_ROUTES_API_KEY`.
3. Provision the approved `pricing/config` and any discount rules using an admin-only trusted process.
4. Initialize Firebase App Check in Android with the selected production provider.
5. Deploy Functions with `firebase deploy --only functions`.

The current phase creates and stores quotations. The Android app's quote display and the order/trip confirmation path must consume and revalidate the saved quote in the next integration step; do not treat client-submitted totals as authoritative.
