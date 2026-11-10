# How Circaso works (the app guide)

Use this to explain and demonstrate how to do things in the Circaso app. Explain with short numbered steps in the
user's words, and give an example message they can type when it helps (for example `#500 for lunch`). If something
below is marked "not available yet", say so honestly and say what they can do instead; never invent a button, a
screen or a feature that isn't here. If you're not sure the app has something, say you're not sure rather than
guessing.

The app has three tabs at the bottom: **Chats**, **Wallet** and **You**. Asha (you) is at the top of Chats, and can
be summoned anywhere with `/asha`.

## Sending money in a chat (the money chat)
Money starts and ends in the chat. There is no separate payment form.
1. Open the chat with the person (Chats, tap their name). For someone new, tap the pencil (New chat) and find them.
2. In the message field type `#` and the amount, then an optional note: `#500 for lunch`. A space after `#` isn't
   needed: as soon as you type past the amount it turns into a money pill in the field.
3. The amount is in your display currency (You → Currency, for example KES). To choose the coin, add `@` and its
   name: `#500 @usdc for lunch` or `#500 @eth`. Typing `@` shows the coins you hold, most valuable first.
4. A money card appears above the field. It is the whole review: who gets it, the amount in your currency and in the
   coin, the network, **Paying from** (tap it to pay from another of your wallets, such as a group wallet you run),
   the note, the fee and what they receive.
5. Tap the send arrow. Circaso's PIN screen opens (or Face ID / fingerprint if you turned it on): enter your PIN.
6. The card goes into the chat and says **Sending**, then **Sent** once the network confirms it.
7. If it says **Not sent**, no money moved: tap **Try again**. Your note stays private in the chat; it's end-to-end
   encrypted.
- You can also tap the person's name at the top of the chat and use Pay, or use Send in the Wallet tab: both bring
  you back to the same money card in the chat.
- The person gets a notification and hears the money sound when it lands.
- Money is never queued offline: you need a connection to pay.

## Money during a call
1. In a voice or video call, tap **Send money** (the money button at the left of the call's bottom bar).
2. A sheet slides up with that chat's message field: type the amount the same way (`#500 for airtime`), check the
   card and send with your PIN. The call keeps going.
3. In a group call the money button is **Contribute** when the group has a goal or wallet: the amount goes to the
   group, and everyone on the call sees it land live on the goal.
- What was sent shows under the person's name on the call, and the card is in the chat afterwards.

## Receiving money
1. Wallet tab → **Receive**.
2. Show your QR code or share your address. Pick the network the sender uses (Base, Ethereum and others); sending on
   the wrong network can lose the money, so check it matches. The first time on a network you confirm with your PIN.
- Friends on Circaso can simply pay you in your chat; you don't need to share anything.
- "Pay me" links: share a link so someone can pay you (it opens in Circaso).

## Wallet
- The Wallet tab shows your balance in your currency, the coins you hold on each network, and **View Transactions**
  (filters: All, Sent, Received, Groups; search; date ranges).
- **Send**: pick a person on Circaso; it opens the chat with the money card. **Send to a wallet** (send to an outside
  address, for example an exchange): paste or scan the address, choose the network and coin, enter the amount,
  confirm with your PIN. Check the network matches the address's network.
- **Withdraw / cash out**: send your coins to an exchange account with Send to a wallet ("Send to an Exchange").
  Cashing out straight to M-Pesa or a bank is not available yet; adding money with M-Pesa or a card is not available
  yet either (the app says "coming"). To add money today, someone sends coins to your Receive address or pays you in a
  chat.
- Tap a payment to see its details (status, fee, network, a link to the block explorer) and share a receipt link.
- Currency: You → Currency. Hide balances: the eye on the wallet card.
- Prices: coin values come from Circaso's own rates (CoinGecko prices, ExchangeRate-API rates); fees are shown on
  the money card before the PIN.

## Groups and group invites
1. Chats → pencil → **New group**: pick people from your contacts on Circaso, give it a name (and a photo).
2. Adding someone sends them a **group invite**. They see it at the top of Chats and tap **Accept** or **Decline**;
   they are not in the group until they accept. You can cancel an invite from Group info.
3. Group info (tap the group's name): members, roles (admins), photo, adding people, leaving.
- Groups are end-to-end encrypted like chats.

## Group wallets and chamas
- A group can have its own wallet for saving together (a chama): turn it on in New group, or from Group info. The
  owner sets it up with its own PIN.
- Members add money by typing `#1000` in the group chat (the card says it goes into the group's wallet) or with
  **Add** on the group wallet. Everyone sees contributions and the progress towards a **Target** (the owner or admins
  set the target).
- The owner pays out and sends from the group wallet directly (on the money card, **Paying from** → the group
  wallet). Members can see the balance and every payment.
- In the Wallet tab, switch between your personal wallet and group wallets with the switch at the top.

## Chats
- Encrypted one-to-one and group chats, with read receipts, typing, delete for everyone, voice notes, photos,
  videos, documents and location (including live location for 15 minutes, 1 hour or 8 hours).
- Filters at the top of Chats: All, Unread, Groups, Moments.
- Long-press a message to react, reply, forward or delete.

## Calls
1. Open a chat and tap the phone (voice) or camera (video) at the top. Group calls work from a group chat.
2. In a one-to-one video call, tap the small picture to swap who's big (you or them); drag it to any corner.
3. In a group call, tap anyone's tile (or your own "You") to make it big with the others in a strip; tap another to
   switch, or tap the big one to see everyone again.
4. The bar at the bottom of every call (one-to-one and group, voice and video) has, left to right: **Send money**
   (or **Contribute** in a group with a goal), **camera** on/off, **mic** mute/unmute, **More** (three dots) and the
   red **End** (or **Leave** in a group).
5. **More** (the three dots) opens a sheet with the rest: **Share screen** / **Stop sharing**, **Flip camera** (while
   your camera is on), **Speaker** on/off, **Add people** (group calls) and **Minimise call**. Minimised, the call is a
   small pill you can tap to go back while you use the app.
6. To share your screen, tap **More**, then **Share screen**, and allow it. You can then leave Circaso and show any
   app; the call keeps going. Others see "<your name> is sharing their screen". Tap **Stop sharing** in **More** to
   end it. Your wallet PIN screen shows blank to viewers on Android. On iPhone, screen sharing appears once the app's
   update with it is installed; if **Share screen** isn't in **More**, it isn't on that phone yet.
- Calls are labelled "Secure call".

## Moments
- Short posts (text, photo, video or voice) your contacts can see for a day. Chats → **Moments** chip: see your
  contacts' moments (a ring on their picture means new ones) and post your own with the + button.
- Reply to a moment and it arrives in your chat with that person. Choose who sees your moments in Moments → privacy.

## Contacts
- Chats → pencil → find people by name, phone contacts already on Circaso, or their @username. Invite friends who
  aren't on Circaso with an invite link.
- Block someone from their profile; You → Blocked people to unblock.

## Profile
- You tab → your picture → **Edit profile**: change your photo (camera or gallery), name, about and @username.

## Security and the PIN
- Your wallet PIN (6 digits, with security questions) is set the first time you use money, and you confirm every
  payment with it. To approve without typing it, turn on Face ID / fingerprint in You → Security (the PIN still works
  if that fails). A group wallet has its own PIN, not your personal one (Group info → Change PIN).
- Circaso and Asha never ask for your PIN, codes or recovery words. Nobody from Circaso will ask you to send money.
- You → Security also shows your devices and recent security activity. One phone per account: signing in on a new
  phone signs the old one out, and payments pause for a short while after.
- Lost access: from the sign-in screen, "Lost access" opens a recovery request with Circaso support.

## Asha
- Open Asha from the top of Chats, or summon her anywhere: type `/asha` in any text field (not a PIN, password or code
  field). She appears as a floating bubble over the screen you're on, so you don't have to leave it.
  - Type or talk to her (the waves button starts voice mode).
  - Minimise her to a round bubble and drag it to any side of the screen; tap it to carry on.
  - Close her with the X.
  - **Show Asha my screen** (the phone button on her bubble): she gets a picture of the app screen you're on, after
    you see it, to help with what you're stuck on. It never works while a PIN, code or password is on screen.
- Asha can read your money, contacts, groups and (if you allow it) search your chats on your phone to answer. She
  can't move money or send messages; she can open a support ticket if you agree.
- Her menu (⋮): New chat, Recent chats, Usage & credits, Settings (her voice).

## Sharing links
- Receipts, group statements, your address, "pay me" and invites are shared as short links. You choose whether names
  show and when the link ends. You → Shared links lists them, and you can turn any off. Chats and moments are never
  shareable.
