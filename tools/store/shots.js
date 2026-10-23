// The eight Google Play phone screenshots (1080 × 1920), in store order. `screen` is a capture in screens/ (dark
// theme, 1080 × 2220 emulator, public fixture wallets renamed); `accent` is the part of the title drawn in mint;
// `pill: false` leaves out the gesture bar where it would cover the page's own bottom content.
window.SHOTS = [
  {
    screen: 'wallets',
    eyebrow: 'Offline by design',
    title: ['Air-gapped signing.', 'For everyone.'],
    accent: 1,
    body: 'Keys are created, kept and used on this phone. The app has no Internet permission at all.',
  },
  {
    screen: 'review-transfer',
    eyebrow: 'Decoded on device',
    title: ['See exactly', 'what you sign.'],
    accent: 1,
    body: 'Amounts, recipients, contracts and fees, decoded offline before you approve anything.',
  },
  {
    screen: 'review-risk',
    eyebrow: 'Risk warnings',
    title: ['Spot the risky', 'request first.'],
    accent: 1,
    body: 'Unlimited approvals and unknown spenders are flagged, checked against 1,720 known contracts.',
  },
  {
    screen: 'authorize',
    pill: false,
    eyebrow: 'No blind signing',
    title: ['One password', 'per signature.'],
    accent: 1,
    body: 'No global unlock and no password-free signing. Every wallet is encrypted on its own.',
  },
  {
    screen: 'signature',
    eyebrow: 'QR in · QR out',
    title: ['Nothing else', 'leaves the phone.'],
    accent: 1,
    body: 'Show the signature as a QR code. Your online wallet broadcasts it.',
  },
  {
    screen: 'pair-wallet',
    eyebrow: 'Watch-only pairing',
    title: ['Pair once.', 'Watch everywhere.'],
    accent: 1,
    body: 'One animated QR code for Bitcoin, EVM, TRON and Solana, in open standards.',
  },
  {
    screen: 'backup',
    pill: false,
    eyebrow: 'Words · QR · Recovery Cards',
    title: ['Back up', 'your way.'],
    accent: 1,
    body: 'Hidden until you tap. Split across three cards, and any two restore your phrase.',
  },
  {
    screen: 'import',
    pill: false,
    eyebrow: 'Safe import',
    title: ['Typed on our', 'own keyboard.'],
    accent: 1,
    body: 'Your recovery phrase never passes through a keyboard app. All 10 BIP39 languages.',
  },
];
