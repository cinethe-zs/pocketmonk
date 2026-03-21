import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../models/conversation.dart';
import '../models/message.dart';
import '../services/chat_provider.dart';
import '../services/model_manager.dart';
import '../theme/app_theme.dart';
import 'chat_input_bar.dart';
import 'message_bubble.dart';
import 'settings_screen.dart';

class ChatScreen extends StatefulWidget {
  final Future<void> Function(String modelPath) onSwitchModel;
  final Future<void> Function()                 onSwapModels;
  const ChatScreen({
    super.key,
    required this.onSwitchModel,
    required this.onSwapModels,
  });

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final _scrollController = ScrollController();
  final _scaffoldKey      = GlobalKey<ScaffoldState>();
  bool  _promptExpanded   = false;

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
            duration: const Duration(milliseconds: 300),
            curve: Curves.easeOut);
      } else {
        _scrollController.jumpTo(target);
      }
    });
  }

  void _exportConversation(BuildContext context, ChatProvider chat) {
    final conv = chat.currentConversation;
    if (conv == null || conv.messages.isEmpty) return;

    final buf = StringBuffer();
    buf.writeln('# ${conv.title}');
    buf.writeln();
    for (final msg in conv.messages) {
      final role = msg.isUser ? '**You**' : '**PocketMonk**';
      buf.writeln(role);
      buf.writeln(msg.content.trim());
      buf.writeln();
    }

    Clipboard.setData(ClipboardData(text: buf.toString()));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Conversation copied to clipboard'),
        duration: Duration(seconds: 2),
      ),
    );
  }

  Future<void> _openSettings(BuildContext context) async {
    final result = await Navigator.push<String>(
      context,
      MaterialPageRoute(builder: (_) => const SettingsScreen()),
    );
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
            chat:        chat,
            scaffoldKey: _scaffoldKey,
          ),
          appBar: _buildAppBar(context, chat),
          body: Column(
            children: [
              if (_promptExpanded && chat.currentConversation != null)
                _SystemPromptBar(
                  key:          ValueKey(chat.currentConversation!.id),
                  initialValue: chat.currentConversation!.systemPrompt ?? '',
                  hasCustomPrompt: chat.currentConversation!.systemPrompt != null,
                  onSave: (text) {
                    chat.setSystemPrompt(text.trim().isEmpty ? null : text.trim());
                    setState(() => _promptExpanded = false);
                  },
                  onCancel: () => setState(() => _promptExpanded = false),
                  onReset: () {
                    chat.setSystemPrompt(null);
                    setState(() => _promptExpanded = false);
                  },
                ),
              Expanded(
                child: chat.isEmpty
                    ? _EmptyState()
                    : ListView.builder(
                        controller: _scrollController,
                        padding: const EdgeInsets.fromLTRB(12, 20, 12, 12),
                        itemCount: chat.messages.length,
                        itemBuilder: (_, i) {
                          final msg    = chat.messages[i];
                          final isLast = i == chat.messages.length - 1;
                          return MessageBubble(
                            message: msg,
                            onToggleStar: () =>
                                chat.toggleStarMessage(msg.id),
                            onRegenerate: isLast &&
                                    msg.isAssistant &&
                                    msg.status != MessageStatus.streaming &&
                                    !chat.isGenerating
                                ? chat.regenerateLastResponse
                                : null,
                            onEdit: msg.isUser && !chat.isGenerating
                                ? (newContent) =>
                                    chat.editMessageAt(i, newContent)
                                : null,
                            onFork: msg.isUser && !chat.isGenerating
                                ? () => chat.forkConversationAt(i)
                                : null,
                          );
                        },
                      ),
              ),
              if (chat.errorMessage != null)
                _ErrorBanner(message: chat.errorMessage!),
              _ContextBar(
                used:          chat.estimatedTokenCount,
                total:         chat.contextLength,
                isCompressing: chat.isCompressing,
                canCompress:   !chat.isEmpty &&
                    !chat.isGenerating &&
                    chat.messages.length > 6,
                onCompress:    () => chat.compressContext(),
              ),
              ChatInputBar(
                isGenerating: chat.isGenerating || chat.isCompressing,
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
    final hasCustomPrompt = chat.currentConversation?.systemPrompt != null;
    final mgr             = context.watch<ModelManager>();
    final gpuFallback     = chat.llm.gpuFallback;

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
              color: chat.llm.isInferring
                  ? AppTheme.accent
                  : AppTheme.success,
            ),
          ),
          const Text('PocketMonk'),
          const SizedBox(width: 6),
          // GPU fallback warning or status label
          if (gpuFallback)
            GestureDetector(
              onTap: () => ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                  content: Text('GPU inference failed — running on CPU'),
                  duration: Duration(seconds: 3),
                ),
              ),
              child: const Icon(Icons.warning_amber_rounded,
                  size: 14, color: Colors.amber),
            )
          else
            Text(
              chat.llm.isInferring ? 'thinking…' : 'on-device',
              style: const TextStyle(
                color:      AppTheme.textMuted,
                fontSize:   12,
                fontWeight: FontWeight.w400,
              ),
            ),
          // Quick-switch chip
          if (mgr.hasSecondaryModel) ...[
            const SizedBox(width: 8),
            _ModelSwapChip(
              primaryName:   mgr.activeModelName,
              secondaryName: mgr.secondaryModelName,
              onSwap:        widget.onSwapModels,
            ),
          ],
        ],
      ),
      actions: [
        // System prompt
        if (chat.currentConversation != null)
          IconButton(
            icon: Icon(
              Icons.tune_rounded,
              color: (_promptExpanded || hasCustomPrompt)
                  ? AppTheme.accent
                  : null,
            ),
            tooltip: hasCustomPrompt
                ? 'Custom system prompt active'
                : 'System prompt',
            onPressed: () =>
                setState(() => _promptExpanded = !_promptExpanded),
          ),
        // Export conversation
        if (!chat.isEmpty)
          IconButton(
            icon:    const Icon(Icons.ios_share_rounded),
            tooltip: 'Copy conversation',
            onPressed: () => _exportConversation(context, chat),
          ),
        // New conversation
        IconButton(
          icon:    const Icon(Icons.add_rounded),
          tooltip: 'New conversation',
          onPressed: () {
            context
                .read<ChatProvider>()
                .newConversation(mgr.activeModelPath);
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

// ── Inline system prompt editor ───────────────────────────────────────────────

class _SystemPromptBar extends StatefulWidget {
  final String       initialValue;
  final bool         hasCustomPrompt;
  final void Function(String) onSave;
  final VoidCallback onCancel;
  final VoidCallback onReset;

  const _SystemPromptBar({
    super.key,
    required this.initialValue,
    required this.hasCustomPrompt,
    required this.onSave,
    required this.onCancel,
    required this.onReset,
  });

  @override
  State<_SystemPromptBar> createState() => _SystemPromptBarState();
}

class _SystemPromptBarState extends State<_SystemPromptBar> {
  late final TextEditingController _ctrl;

  @override
  void initState() {
    super.initState();
    _ctrl = TextEditingController(text: widget.initialValue);
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      color: AppTheme.surfaceRaised,
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              const Icon(Icons.tune_rounded,
                  size: 14, color: AppTheme.accent),
              const SizedBox(width: 6),
              const Text('System Prompt',
                  style: TextStyle(
                      color:      AppTheme.textPrimary,
                      fontSize:   13,
                      fontWeight: FontWeight.w700)),
              const Spacer(),
              const Text(
                'Overrides default for this conversation',
                style: TextStyle(color: AppTheme.textMuted, fontSize: 11),
              ),
            ],
          ),
          const SizedBox(height: 10),
          TextField(
            controller: _ctrl,
            maxLines:   5,
            minLines:   2,
            style: const TextStyle(
                color: AppTheme.textPrimary, fontSize: 13),
            decoration: InputDecoration(
              hintText:  'You are a helpful assistant…',
              hintStyle: const TextStyle(
                  color: AppTheme.textMuted, fontSize: 12),
              filled:    true,
              fillColor: AppTheme.surface,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
                borderSide: const BorderSide(color: AppTheme.border),
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
                borderSide: const BorderSide(color: AppTheme.accent),
              ),
              contentPadding: const EdgeInsets.all(10),
              isDense: true,
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              if (widget.hasCustomPrompt)
                TextButton(
                  onPressed: widget.onReset,
                  style: TextButton.styleFrom(
                      padding: EdgeInsets.zero,
                      minimumSize: const Size(0, 32)),
                  child: const Text('Reset to default',
                      style: TextStyle(
                          color: AppTheme.textMuted, fontSize: 12)),
                ),
              const Spacer(),
              TextButton(
                onPressed: widget.onCancel,
                style: TextButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    minimumSize: const Size(0, 32)),
                child: const Text('Cancel',
                    style: TextStyle(
                        color: AppTheme.textSecondary, fontSize: 13)),
              ),
              const SizedBox(width: 8),
              ElevatedButton(
                onPressed: () => widget.onSave(_ctrl.text),
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppTheme.accent,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(8)),
                  padding: const EdgeInsets.symmetric(
                      horizontal: 16, vertical: 8),
                  minimumSize: const Size(0, 32),
                  elevation: 0,
                ),
                child: const Text('Save',
                    style: TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w600)),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

// ── Model quick-switch chip ───────────────────────────────────────────────────

class _ModelSwapChip extends StatelessWidget {
  final String            primaryName;
  final String            secondaryName;
  final Future<void> Function() onSwap;

  const _ModelSwapChip({
    required this.primaryName,
    required this.secondaryName,
    required this.onSwap,
  });

  // Abbreviate "Gemma 3 4B" → "Gemma"
  String _short(String name) => name.split(' ').first;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onSwap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color:        AppTheme.accentDim,
          borderRadius: BorderRadius.circular(20),
          border:       Border.all(color: AppTheme.accent.withAlpha(80)),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              _short(primaryName),
              style: const TextStyle(
                color:      AppTheme.accent,
                fontSize:   10,
                fontWeight: FontWeight.w600,
              ),
            ),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 3),
              child: Icon(Icons.swap_horiz_rounded,
                  size: 12, color: AppTheme.accent),
            ),
            Text(
              _short(secondaryName),
              style: const TextStyle(
                color:      AppTheme.textMuted,
                fontSize:   10,
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Conversation drawer ───────────────────────────────────────────────────────

class _ConversationDrawer extends StatefulWidget {
  final ChatProvider chat;
  final GlobalKey<ScaffoldState> scaffoldKey;

  const _ConversationDrawer({required this.chat, required this.scaffoldKey});

  @override
  State<_ConversationDrawer> createState() => _ConversationDrawerState();
}

class _ConversationDrawerState extends State<_ConversationDrawer> {
  final _searchController = TextEditingController();
  String  _query     = '';
  String? _activeTag;   // null = show all

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final chat      = widget.chat;
    final allConvs  = chat.allConversations;
    final currentId = chat.currentConversation?.id;

    // Collect all tags across conversations
    final allTags = <String>{};
    for (final c in allConvs) {
      allTags.addAll(c.tags);
    }

    // Filter by active tag then by search query
    var convs = _activeTag == null
        ? allConvs
        : allConvs.where((c) => c.tags.contains(_activeTag)).toList();

    if (_query.isNotEmpty) {
      final q = _query.toLowerCase();
      convs = convs.where((c) {
        if (c.title.toLowerCase().contains(q)) return true;
        return c.messages.any((m) => m.content.toLowerCase().contains(q));
      }).toList();
    }

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
                      style: TextStyle(
                          fontSize: 13, fontWeight: FontWeight.w600)),
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

            // Search bar
            if (allConvs.isNotEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
                child: TextField(
                  controller: _searchController,
                  onChanged: (v) =>
                      setState(() => _query = v.trim()),
                  style: const TextStyle(
                      color: AppTheme.textPrimary, fontSize: 13),
                  decoration: InputDecoration(
                    hintText:  'Search conversations…',
                    hintStyle: const TextStyle(
                        color: AppTheme.textMuted, fontSize: 13),
                    prefixIcon: const Icon(Icons.search_rounded,
                        color: AppTheme.textMuted, size: 18),
                    suffixIcon: _query.isNotEmpty
                        ? IconButton(
                            icon: const Icon(Icons.clear_rounded,
                                color: AppTheme.textMuted, size: 16),
                            onPressed: () {
                              _searchController.clear();
                              setState(() => _query = '');
                            },
                          )
                        : null,
                    filled:    true,
                    fillColor: AppTheme.surfaceRaised,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(10),
                      borderSide:
                          const BorderSide(color: AppTheme.border),
                    ),
                    enabledBorder: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(10),
                      borderSide:
                          const BorderSide(color: AppTheme.border),
                    ),
                    focusedBorder: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(10),
                      borderSide:
                          const BorderSide(color: AppTheme.accent),
                    ),
                    contentPadding: const EdgeInsets.symmetric(
                        horizontal: 12, vertical: 8),
                    isDense: true,
                  ),
                ),
              ),

            // Tag filter row
            if (allTags.isNotEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(12, 6, 12, 2),
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      _TagChip(
                        label:    'All',
                        selected: _activeTag == null,
                        onTap:    () =>
                            setState(() => _activeTag = null),
                      ),
                      ...allTags.map((tag) => _TagChip(
                            label:    tag,
                            selected: _activeTag == tag,
                            onTap: () => setState(() =>
                                _activeTag =
                                    _activeTag == tag ? null : tag),
                          )),
                    ],
                  ),
                ),
              ),

            const Divider(color: AppTheme.border, height: 16),

            // Conversation list
            Expanded(
              child: convs.isEmpty
                  ? Center(
                      child: Text(
                        _query.isNotEmpty
                            ? 'No results for "$_query"'
                            : _activeTag != null
                                ? 'No conversations tagged "$_activeTag"'
                                : 'No conversations yet',
                        style: const TextStyle(
                            color: AppTheme.textMuted, fontSize: 13),
                        textAlign: TextAlign.center,
                      ),
                    )
                  : ListView.builder(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 8, vertical: 4),
                      itemCount: convs.length,
                      itemBuilder: (context, i) {
                        final conv     = convs[i];
                        final isActive = conv.id == currentId;
                        return Dismissible(
                          key:       Key(conv.id),
                          direction: DismissDirection.endToStart,
                          background: Container(
                            alignment: Alignment.centerRight,
                            padding:
                                const EdgeInsets.only(right: 16),
                            color: AppTheme.error.withAlpha(40),
                            child: const Icon(
                                Icons.delete_outline_rounded,
                                color: AppTheme.error),
                          ),
                          onDismissed: (_) =>
                              chat.deleteConversation(conv.id),
                          child: _ConversationTile(
                            conv:      conv,
                            isActive:  isActive,
                            onTap: () {
                              chat.loadConversation(conv);
                              Navigator.pop(context);
                            },
                            onLongPress: () =>
                                _showTagSheet(context, chat, conv),
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

  void _showTagSheet(
    BuildContext context,
    ChatProvider chat,
    Conversation conv,
  ) {
    final addController = TextEditingController();

    showModalBottomSheet(
      context:         context,
      backgroundColor: AppTheme.surface,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) => Padding(
          padding: EdgeInsets.fromLTRB(
              20, 16, 20,
              20 + MediaQuery.of(context).viewInsets.bottom),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Center(
                child: Container(
                  width: 36, height: 4,
                  margin: const EdgeInsets.only(bottom: 14),
                  decoration: BoxDecoration(
                    color: AppTheme.border,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              Text(
                conv.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    color:      AppTheme.textPrimary,
                    fontSize:   15,
                    fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 12),
              if (conv.tags.isNotEmpty) ...[
                const Text('Tags',
                    style: TextStyle(
                        color:      AppTheme.textMuted,
                        fontSize:   11,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 0.6)),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6, runSpacing: 6,
                  children: conv.tags
                      .map((t) => InputChip(
                            label: Text(t,
                                style: const TextStyle(
                                    color:    AppTheme.textPrimary,
                                    fontSize: 12)),
                            backgroundColor: AppTheme.surfaceRaised,
                            deleteIconColor: AppTheme.textMuted,
                            side: const BorderSide(
                                color: AppTheme.border),
                            onDeleted: () {
                              chat.removeTagFromConversation(
                                  conv, t);
                              setSheetState(() {});
                              setState(() {});
                            },
                          ))
                      .toList(),
                ),
                const SizedBox(height: 14),
              ],
              // Add tag
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: addController,
                      autofocus:  conv.tags.isEmpty,
                      style: const TextStyle(
                          color: AppTheme.textPrimary, fontSize: 14),
                      decoration: InputDecoration(
                        hintText:  'Add a tag…',
                        hintStyle: const TextStyle(
                            color: AppTheme.textMuted),
                        filled:    true,
                        fillColor: AppTheme.surfaceRaised,
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(10),
                          borderSide: const BorderSide(
                              color: AppTheme.border),
                        ),
                        focusedBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(10),
                          borderSide: const BorderSide(
                              color: AppTheme.accent),
                        ),
                        contentPadding: const EdgeInsets.symmetric(
                            horizontal: 12, vertical: 10),
                        isDense: true,
                      ),
                      onSubmitted: (v) {
                        if (v.trim().isEmpty) return;
                        chat.addTagToConversation(conv, v.trim());
                        addController.clear();
                        setSheetState(() {});
                        setState(() {});
                      },
                    ),
                  ),
                  const SizedBox(width: 8),
                  ElevatedButton(
                    onPressed: () {
                      final v = addController.text.trim();
                      if (v.isEmpty) return;
                      chat.addTagToConversation(conv, v);
                      addController.clear();
                      setSheetState(() {});
                      setState(() {});
                    },
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppTheme.accent,
                      foregroundColor: Colors.white,
                      shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(10)),
                      elevation: 0,
                      padding: const EdgeInsets.symmetric(
                          horizontal: 16, vertical: 12),
                    ),
                    child: const Text('Add',
                        style: TextStyle(fontWeight: FontWeight.w600)),
                  ),
                ],
              ),
              const SizedBox(height: 8),
            ],
          ),
        ),
      ),
    ).then((_) => addController.dispose());
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

// ── Conversation tile ─────────────────────────────────────────────────────────

class _ConversationTile extends StatelessWidget {
  final Conversation conv;
  final bool         isActive;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  const _ConversationTile({
    required this.conv,
    required this.isActive,
    required this.onTap,
    required this.onLongPress,
  });

  @override
  Widget build(BuildContext context) {
    return ListTile(
      selected:          isActive,
      selectedTileColor: AppTheme.accentDim,
      shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(10)),
      onTap:      onTap,
      onLongPress: onLongPress,
      title: Text(
        conv.title,
        style: TextStyle(
          color:      isActive ? AppTheme.accent : AppTheme.textPrimary,
          fontSize:   13,
          fontWeight: isActive ? FontWeight.w600 : FontWeight.w400,
        ),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _formatDate(conv.updatedAt),
            style: const TextStyle(
                color: AppTheme.textMuted, fontSize: 11),
          ),
          if (conv.tags.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Wrap(
                spacing: 4, runSpacing: 2,
                children: conv.tags
                    .map((t) => Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 6, vertical: 1),
                          decoration: BoxDecoration(
                            color: AppTheme.accentDim,
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(t,
                              style: const TextStyle(
                                  color:    AppTheme.accent,
                                  fontSize: 10,
                                  fontWeight: FontWeight.w500)),
                        ))
                    .toList(),
              ),
            ),
        ],
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

// ── Tag chip ──────────────────────────────────────────────────────────────────

class _TagChip extends StatelessWidget {
  final String   label;
  final bool     selected;
  final VoidCallback onTap;

  const _TagChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 6),
      child: GestureDetector(
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
          decoration: BoxDecoration(
            color:        selected ? AppTheme.accentDim : AppTheme.surfaceRaised,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(
              color: selected ? AppTheme.accent : AppTheme.border,
            ),
          ),
          child: Text(
            label,
            style: TextStyle(
              color:      selected ? AppTheme.accent : AppTheme.textMuted,
              fontSize:   11,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
            ),
          ),
        ),
      ),
    );
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
              color:        AppTheme.surfaceRaised,
              borderRadius: BorderRadius.circular(20),
              border:       Border.all(color: AppTheme.border),
            ),
            child: const Icon(Icons.self_improvement_rounded,
                size: 34, color: AppTheme.accent),
          ),
          const SizedBox(height: 20),
          const Text('PocketMonk',
              style: TextStyle(
                color:        AppTheme.textPrimary,
                fontSize:     22,
                fontWeight:   FontWeight.w700,
                letterSpacing: -0.3,
              )),
          const SizedBox(height: 8),
          const Text(
              '100% private · runs on your device · no internet',
              style: TextStyle(
                  color: AppTheme.textMuted, fontSize: 13)),
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

// ── Context usage bar ─────────────────────────────────────────────────────────

class _ContextBar extends StatelessWidget {
  final int          used;
  final int          total;
  final bool         isCompressing;
  final bool         canCompress;
  final VoidCallback onCompress;

  const _ContextBar({
    required this.used,
    required this.total,
    required this.isCompressing,
    required this.canCompress,
    required this.onCompress,
  });

  @override
  Widget build(BuildContext context) {
    final ratio = total > 0 ? (used / total).clamp(0.0, 1.0) : 0.0;
    if (ratio < 0.1 && !isCompressing) return const SizedBox.shrink();

    final color = ratio > 0.85
        ? AppTheme.error
        : ratio > 0.60
            ? Colors.amber
            : AppTheme.success;

    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 4, 12, 0),
      child: Row(
        children: [
          Expanded(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(2),
              child: LinearProgressIndicator(
                value:           ratio,
                minHeight:       3,
                backgroundColor: AppTheme.border,
                valueColor:      AlwaysStoppedAnimation<Color>(color),
              ),
            ),
          ),
          const SizedBox(width: 8),
          Text(
            '~$used / $total tokens',
            style: TextStyle(color: color, fontSize: 10),
          ),
          if (isCompressing) ...[
            const SizedBox(width: 8),
            const SizedBox(
              width: 10, height: 10,
              child: CircularProgressIndicator(
                strokeWidth: 1.5,
                valueColor: AlwaysStoppedAnimation<Color>(AppTheme.accent),
              ),
            ),
          ] else if (canCompress && ratio > 0.50) ...[
            const SizedBox(width: 8),
            GestureDetector(
              onTap: onCompress,
              child: Container(
                padding: const EdgeInsets.symmetric(
                    horizontal: 7, vertical: 2),
                decoration: BoxDecoration(
                  color:        AppTheme.accentDim,
                  borderRadius: BorderRadius.circular(4),
                  border:       Border.all(
                      color: AppTheme.accent.withAlpha(120)),
                ),
                child: const Text(
                  'Compress',
                  style: TextStyle(
                    color:      AppTheme.accent,
                    fontSize:   10,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ),
          ],
        ],
      ),
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
                style:
                    const TextStyle(color: AppTheme.error, fontSize: 12),
                maxLines: 2,
                overflow: TextOverflow.ellipsis),
          ),
        ],
      ),
    );
  }
}
