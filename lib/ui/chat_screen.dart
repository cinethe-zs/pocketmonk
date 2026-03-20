import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/chat_provider.dart';
import '../services/model_manager.dart';
import '../theme/app_theme.dart';
import 'chat_input_bar.dart';
import 'message_bubble.dart';
import 'settings_screen.dart';

class ChatScreen extends StatefulWidget {
  final Future<void> Function(String modelPath) onSwitchModel;
  const ChatScreen({super.key, required this.onSwitchModel});

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final _scrollController = ScrollController();
  final _scaffoldKey      = GlobalKey<ScaffoldState>();

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  void _scrollToBottom({bool animated = true}) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) return;
      final target = _scrollController.position.maxScrollExtent;
      if (animated) {
        _scrollController.animateTo(target,
            duration: const Duration(milliseconds: 300), curve: Curves.easeOut);
      } else {
        _scrollController.jumpTo(target);
      }
    });
  }

  Future<void> _openSettings(BuildContext context) async {
    final result = await Navigator.push<String>(
      context,
      MaterialPageRoute(builder: (_) => const SettingsScreen()),
    );
    // result is the new model path if the user tapped a different model
    if (result != null && context.mounted) {
      await widget.onSwitchModel(result);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Consumer<ChatProvider>(
      builder: (context, chat, _) {
        if (chat.isGenerating) _scrollToBottom();

        return Scaffold(
          key:    _scaffoldKey,
          drawer: _ConversationDrawer(
            chat:          chat,
            scaffoldKey:   _scaffoldKey,
          ),
          appBar: _buildAppBar(context, chat),
          body: Column(
            children: [
              Expanded(
                child: chat.isEmpty
                    ? _EmptyState()
                    : ListView.builder(
                        controller:  _scrollController,
                        padding: const EdgeInsets.fromLTRB(12, 20, 12, 12),
                        itemCount:   chat.messages.length,
                        itemBuilder: (_, i) =>
                            MessageBubble(message: chat.messages[i]),
                      ),
              ),
              if (chat.errorMessage != null)
                _ErrorBanner(message: chat.errorMessage!),
              ChatInputBar(
                isGenerating: chat.isGenerating,
                onSend:       chat.sendMessage,
                onStop:       chat.stopGeneration,
              ),
            ],
          ),
        );
      },
    );
  }

  PreferredSizeWidget _buildAppBar(BuildContext context, ChatProvider chat) {
    return AppBar(
      leading: IconButton(
        icon:    const Icon(Icons.menu_rounded),
        tooltip: 'Conversations',
        onPressed: () => _scaffoldKey.currentState?.openDrawer(),
      ),
      title: Row(
        children: [
          Container(
            width: 8, height: 8,
            margin: const EdgeInsets.only(right: 8),
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: chat.llm.isInferring ? AppTheme.accent : AppTheme.success,
            ),
          ),
          const Text('PocketMonk'),
          const SizedBox(width: 6),
          Text(
            chat.llm.isInferring ? 'thinking…' : 'on-device',
            style: const TextStyle(
              color:      AppTheme.textMuted,
              fontSize:   12,
              fontWeight: FontWeight.w400,
            ),
          ),
        ],
      ),
      actions: [
        // New conversation
        IconButton(
          icon:    const Icon(Icons.add_rounded),
          tooltip: 'New conversation',
          onPressed: () {
            final mgr = context.read<ModelManager>();
            context.read<ChatProvider>().newConversation(mgr.activeModelPath);
          },
        ),
        // Settings
        IconButton(
          icon:    const Icon(Icons.settings_rounded),
          tooltip: 'Settings',
          onPressed: () => _openSettings(context),
        ),
        const SizedBox(width: 4),
      ],
    );
  }
}

// ── Conversation drawer ───────────────────────────────────────────────────────

class _ConversationDrawer extends StatelessWidget {
  final ChatProvider chat;
  final GlobalKey<ScaffoldState> scaffoldKey;

  const _ConversationDrawer({required this.chat, required this.scaffoldKey});

  @override
  Widget build(BuildContext context) {
    final convs = chat.allConversations;
    final currentId = chat.currentConversation?.id;

    return Drawer(
      backgroundColor: AppTheme.surface,
      child: SafeArea(
        child: Column(
          children: [
            // Header
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 8, 8),
              child: Row(
                children: [
                  const Icon(Icons.self_improvement_rounded,
                      color: AppTheme.accent, size: 22),
                  const SizedBox(width: 10),
                  const Expanded(
                    child: Text('Conversations',
                        style: TextStyle(
                          color:      AppTheme.textPrimary,
                          fontSize:   16,
                          fontWeight: FontWeight.w700,
                        )),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close_rounded,
                        color: AppTheme.textMuted, size: 20),
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
            ),

            // New conversation button
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
              child: SizedBox(
                width: double.infinity,
                height: 44,
                child: ElevatedButton.icon(
                  onPressed: () {
                    final mgr = context.read<ModelManager>();
                    chat.newConversation(mgr.activeModelPath);
                    Navigator.pop(context);
                  },
                  icon:  const Icon(Icons.add_rounded, size: 18),
                  label: const Text('New conversation',
                      style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppTheme.accent,
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12)),
                    elevation: 0,
                  ),
                ),
              ),
            ),

            const Divider(color: AppTheme.border, height: 20),

            // Conversation list
            Expanded(
              child: convs.isEmpty
                  ? const Center(
                      child: Text('No conversations yet',
                          style: TextStyle(
                              color: AppTheme.textMuted, fontSize: 13)),
                    )
                  : ListView.builder(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 8, vertical: 4),
                      itemCount: convs.length,
                      itemBuilder: (context, i) {
                        final conv = convs[i];
                        final isActive = conv.id == currentId;
                        return Dismissible(
                          key:       Key(conv.id),
                          direction: DismissDirection.endToStart,
                          background: Container(
                            alignment: Alignment.centerRight,
                            padding: const EdgeInsets.only(right: 16),
                            color: AppTheme.error.withAlpha(40),
                            child: const Icon(Icons.delete_outline_rounded,
                                color: AppTheme.error),
                          ),
                          onDismissed: (_) => chat.deleteConversation(conv.id),
                          child: ListTile(
                            selected:        isActive,
                            selectedTileColor: AppTheme.accentDim,
                            shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(10)),
                            title: Text(
                              conv.title,
                              style: TextStyle(
                                color: isActive
                                    ? AppTheme.accent
                                    : AppTheme.textPrimary,
                                fontSize:   13,
                                fontWeight: isActive
                                    ? FontWeight.w600
                                    : FontWeight.w400,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            subtitle: Text(
                              _formatDate(conv.updatedAt),
                              style: const TextStyle(
                                  color: AppTheme.textMuted, fontSize: 11),
                            ),
                            onTap: () {
                              chat.loadConversation(conv);
                              Navigator.pop(context);
                            },
                          ),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }

  String _formatDate(DateTime dt) {
    final now  = DateTime.now();
    final diff = now.difference(dt);
    if (diff.inMinutes < 1)  return 'Just now';
    if (diff.inHours   < 1)  return '${diff.inMinutes}m ago';
    if (diff.inDays    < 1)  return '${diff.inHours}h ago';
    if (diff.inDays    < 7)  return '${diff.inDays}d ago';
    return '${dt.day}/${dt.month}/${dt.year}';
  }
}

// ── Empty state ───────────────────────────────────────────────────────────────

class _EmptyState extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 72, height: 72,
            decoration: BoxDecoration(
              color: AppTheme.surfaceRaised,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: AppTheme.border),
            ),
            child: const Icon(Icons.self_improvement_rounded,
                size: 34, color: AppTheme.accent),
          ),
          const SizedBox(height: 20),
          const Text('PocketMonk',
              style: TextStyle(
                color:       AppTheme.textPrimary,
                fontSize:    22,
                fontWeight:  FontWeight.w700,
                letterSpacing: -0.3,
              )),
          const SizedBox(height: 8),
          const Text('100% private · runs on your device · no internet',
              style: TextStyle(color: AppTheme.textMuted, fontSize: 13)),
          const SizedBox(height: 32),
          _SuggestionChips(),
        ],
      ),
    );
  }
}

class _SuggestionChips extends StatelessWidget {
  final _suggestions = const [
    'Explain async/await in Dart',
    'Write a Python quicksort',
    'What is the Tensor G2 chip?',
    'Summarise the SOLID principles',
  ];

  @override
  Widget build(BuildContext context) {
    final chat = context.read<ChatProvider>();
    return Wrap(
      spacing: 8, runSpacing: 8,
      alignment: WrapAlignment.center,
      children: _suggestions
          .map((s) => ActionChip(
                label: Text(s,
                    style: const TextStyle(
                        color: AppTheme.textSecondary, fontSize: 13)),
                backgroundColor: AppTheme.surfaceRaised,
                side: const BorderSide(color: AppTheme.border),
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(20)),
                onPressed: () => chat.sendMessage(s),
              ))
          .toList(),
    );
  }
}

// ── Error banner ──────────────────────────────────────────────────────────────

class _ErrorBanner extends StatelessWidget {
  final String message;
  const _ErrorBanner({required this.message});

  @override
  Widget build(BuildContext context) {
    return Container(
      width:   double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      color:   AppTheme.error.withAlpha(30),
      child: Row(
        children: [
          const Icon(Icons.error_outline_rounded,
              color: AppTheme.error, size: 16),
          const SizedBox(width: 8),
          Expanded(
            child: Text(message,
                style: const TextStyle(color: AppTheme.error, fontSize: 12),
                maxLines: 2,
                overflow: TextOverflow.ellipsis),
          ),
        ],
      ),
    );
  }
}
