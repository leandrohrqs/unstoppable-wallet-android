# Unstoppable Wallet

We dream of a world… A world where private property is untouchable and market access is unconditional.

That obsession led us to engineer a crypto wallet that is equally open to all, lives online forever and unconditionally protects your assets. Only the user is in control of the money.

Unstoppable is a powerful non-custodial multi-wallet for Bitcoin, Ethereum, Binance Smart Chain, Avalanche, Solana, Zcash, The Open Network several and other blockchains. It provides non-custodial crypto storage, on-chain decentralized swaps, institutional grade analytics for cryptocurrency markets, extensive privacy controls and human oriented design.

It is built with care and adheres to best programming practices and implementation standards in cryptocurrency world. Fully implemented on Kotlin.

More at [https://unstoppable.money](https://unstoppable.money)

## Supported Android Versions

Devices with Android versions 8.1 and above

## Download

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/io.horizontalsystems.bankwallet/)
[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=io.horizontalsystems.bankwallet)

## Source Code

[https://github.com/horizontalsystems/unstoppable-wallet-android](https://github.com/horizontalsystems/unstoppable-wallet-android)

## NFC Payment Configuration

To enable NFC payment transaction monitoring features, you need to configure an Alchemy API key:

1. Get your API key from [Alchemy](https://www.alchemy.com/)
2. Copy the example configuration file:
    ```bash
    cp app/src/main/assets/local.properties.example app/src/main/assets/local.properties
    ```
3. Open `app/src/main/assets/local.properties` and replace `YOUR_ALCHEMY_API_KEY_HERE` with your actual Alchemy API key:
    ```
    alchemy.api.key=your_actual_api_key_here
    ```

**Note:** The `local.properties` file is git-ignored and will not be committed to the repository. This ensures your API key remains private.

Without the Alchemy API key configured, NFC payment features will work but transaction monitoring (for merchant/POS mode) will be limited.

## License

This wallet is open source and available under the terms of the MIT License.
