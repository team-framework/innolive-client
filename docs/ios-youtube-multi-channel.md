# iOS YouTube channel selection

The platform settings screen lists each connected YouTube channel with a separate disconnect confirmation. Channel addition invokes Google authorization with the previous SDK session signed out so the user can choose another account. The app access token is unchanged.

Broadcast settings shows the channel picker before preparing YouTube or adding YouTube during CHZZK broadcasting. When multiple channels are connected, each preparation/addition asks the user to confirm the selected channel name. Selection updates the settings scope and reloads defaults. Saving succeeds before prepare receives the selected account ID. A prepared/live YouTube target locks channel selection; the target title displays the server channel_title. Refresh preserves the selected connection while it exists. Removing another channel preserves it.

Verification: focused URLProtocol tests cover account-specific DELETE, prepare/mode payloads, refresh/selection, and late defaults from a previous channel. Actual Google/brand-channel consent and production two-channel streaming require account/device verification.
