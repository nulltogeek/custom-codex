# Pull down to refresh (0.7.4 / code 12)

At the top of **Chats** or **Projects**, pull downward and release to refresh
saved chats, project inventory and live status from the connected computer. The
gesture works on empty lists and every Chats tab, including project-filtered
chats. A spinner appears at the top until the request finishes. The toolbar
refresh button remains available, and accessibility services can invoke the
list's **Refresh list** action.

The selected tab, search, project filter and already loaded pages stay in place.
The [0.7.3 catalogue reconciliation](mobile-0.7.3.md) updates first-page records
while retaining older scrolled records. Pulling while partway down a list scrolls
toward the top; the refresh gesture starts when the list reaches the top.

Refresh is disabled while disconnected, signed out or another action/refresh is
running. Its state is claimed before starting network work, so repeated pulls
and toolbar taps cannot launch duplicate refreshes. The spinner stops on success
or failure; failures use the existing visible error notice. Refresh pauses
automatic pagination and invalidates stale page requests before reconciling the
refreshed catalogue.

Update through **Settings → App updates**. This is an Android-only change and
requires no backend restart or Nginx reload. See
[release verification](mobile-0.7.4-verification.md) for checks and limits.
