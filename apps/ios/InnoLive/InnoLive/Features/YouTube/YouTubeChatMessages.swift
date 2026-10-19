import SwiftUI
import WebKit

struct YouTubeChatMessage: Decodable, Equatable, Identifiable {
    let id: String
    let author: String
    let text: String
}

struct YouTubeChatSnapshot: Decodable {
    let url: String
    let messages: [YouTubeChatMessage]
    let ready: Bool

    var visibleMessages: [YouTubeChatMessage] {
        var seen = Set<String>()
        return messages.suffix(50).compactMap { message in
            let id = String(message.id.prefix(256))
            guard !id.isEmpty, seen.insert(id).inserted else { return nil }
            let text = message.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return nil }
            return YouTubeChatMessage(id: id,
                                      author: String(message.author.prefix(100)),
                                      text: String(text.prefix(2_000)))
        }
    }
}

struct YouTubeChatMessageList: View {
    let messages: [YouTubeChatMessage]

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 6) {
                    ForEach(messages) { message in
                        (Text(message.author + "  ").bold().foregroundColor(.cyan)
                         + Text(message.text).foregroundColor(.white))
                            .font(.callout)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(.black.opacity(0.62), in: RoundedRectangle(cornerRadius: 10))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .id(message.id)
                            .accessibilityIdentifier("youtube-chat-message-" + message.id)
                    }
                    Color.clear.frame(height: 1).id("latest-chat")
                }
            }
            .scrollIndicators(.hidden)
            .defaultScrollAnchor(.bottom)
            .onChange(of: messages) { _, _ in
                proxy.scrollTo("latest-chat", anchor: .bottom)
            }
        }
        .accessibilityIdentifier("youtube-chat-messages")
    }
}

/// Extract text in an isolated JavaScript world; page HTML is never rendered by SwiftUI.
/// YouTube owns these DOM selectors, so live reception must be rechecked when its page changes.
enum YouTubeChatBridge {
    static let name = "viewerChatMessages"
    static let script = #"""
    (() => {
        let pending = false;
        let previous = "";
        const text = (node) => {
            if (!node) return "";
            if (node.nodeType === Node.TEXT_NODE) return node.textContent || "";
            if (node.nodeType === Node.ELEMENT_NODE && node.tagName === "IMG") {
                return node.getAttribute("alt") || "";
            }
            return Array.from(node.childNodes).map(text).join("");
        };
        const publish = () => {
            pending = false;
            const renderers = Array.from(document.querySelectorAll(
                "yt-live-chat-text-message-renderer, yt-live-chat-paid-message-renderer, yt-live-chat-membership-item-renderer, yt-live-chat-paid-sticker-renderer"
            )).slice(-50);
            const messages = renderers.filter(row => !row.hasAttribute("is-deleted"))
                .map((row, index) => {
                    const author = text(row.querySelector("#author-name")).trim();
                    const body = text(row.querySelector("#message")).trim()
                        || text(row.querySelector("#header-subtext")).trim()
                        || text(row.querySelector("#sticker")).trim();
                    return { id: row.id || `${index}:${author}:${body}`, author, text: body };
                }).filter(row => row.text.length > 0);
            const value = JSON.stringify({url: location.href, messages,
                ready: !!document.querySelector("yt-live-chat-renderer")});
            if (value !== previous) {
                previous = value;
                window.webkit.messageHandlers.viewerChatMessages.postMessage(value);
            }
        };
        const schedule = () => {
            if (pending) return;
            pending = true;
            setTimeout(publish, 100);
        };
        new MutationObserver(schedule).observe(document.documentElement, {
            childList: true, subtree: true, characterData: true,
            attributes: true, attributeFilter: ["is-deleted"]
        });
        publish();
    })();
    """#
}
