import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown/flutter_markdown.dart';

import '../models/message.dart';
import '../theme/app_theme.dart';

class MessageBubble extends StatelessWidget {
  final Message message;
  final VoidCallback? onRegenerate;
  final void Function(String newContent)? onEdit;
  final VoidCallback? onToggleStar;
  final VoidCallback? onFork;

  const MessageBubble({
    super.key,
    required this.message,
    this.onRegenerate,
    this.onEdit,
    this.onToggleStar,
    this.onFork,
  });

  @override
  Widget build(BuildContext context) {
    if (message.isSummary) return _SummaryCard(message: message);
    return message.isUser
        ? _UserBubble(
            message:      message,
            onEdit:       onEdit,
            onToggleStar: onToggleStar,
            onFork:       onFork,
          )
        : _AssistantBubble(
            message:      message,
            onRegenerate: onRegenerate,
            onToggleStar: onToggleStar,
          );
  }
}

// ── Long-press context menu ───────────────────────────────────────────────────

void _showMessageMenu(
  BuildContext context, {
  required Message message,
  VoidCallback? onEditRequested,
  VoidCallback? onToggleStar,
  VoidCallback? onFork,
}) {
  showModalBottomSheet(
    context:           context,
    backgroundColor:   AppTheme.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (_) => SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Handle
          Container(
            width: 36, height: 4,
            margin: const EdgeInsets.only(top: 12, bottom: 8),
            decoration: BoxDecoration(
              color: AppTheme.border,
              borderRadius: BorderRadius.circular(2),
            ),
          ),

          // Star / Unstar
          if (onToggleStar != null)
            ListTile(
              leading: Icon(
                message.starred
                    ? Icons.star_rounded
                    : Icons.star_outline_rounded,
                color: message.starred ? Colors.amber : AppTheme.textMuted,
              ),
              title: Text(
                message.starred ? 'Unstar message' : 'Star message',
                style: const TextStyle(color: AppTheme.textPrimary, fontSize: 14),
              ),
              onTap: () {
                Navigator.pop(context);
                onToggleStar();
              },
            ),

          // Edit (user messages only)
          if (onEditRequested != null)
            ListTile(
              leading: const Icon(Icons.edit_rounded, color: AppTheme.textMuted),
              title: const Text('Edit message',
                  style: TextStyle(color: AppTheme.textPrimary, fontSize: 14)),
              onTap: () {
                Navigator.pop(context);
                onEditRequested();
              },
            ),

          // Copy
          ListTile(
            leading: const Icon(Icons.copy_rounded, color: AppTheme.textMuted),
            title: const Text('Copy',
                style: TextStyle(color: AppTheme.textPrimary, fontSize: 14)),
            onTap: () {
              Navigator.pop(context);
              Clipboard.setData(ClipboardData(text: message.content));
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                  content: Text('Copied to clipboard'),
                  duration: Duration(seconds: 2),
                ),
              );
            },
          ),

          // Fork from here
          if (onFork != null)
            ListTile(
              leading: const Icon(Icons.fork_right_rounded,
                  color: AppTheme.textMuted),
              title: const Text('Fork from here',
                  style: TextStyle(color: AppTheme.textPrimary, fontSize: 14)),
              onTap: () {
                Navigator.pop(context);
                onFork();
              },
            ),

          const SizedBox(height: 8),
        ],
      ),
    ),
  );
}

// ── User bubble ──────────────────────────────────────────────────────────────

class _UserBubble extends StatefulWidget {
  final Message message;
  final void Function(String)? onEdit;
  final VoidCallback? onToggleStar;
  final VoidCallback? onFork;

  const _UserBubble({
    required this.message,
    this.onEdit,
    this.onToggleStar,
    this.onFork,
  });

  @override
  State<_UserBubble> createState() => _UserBubbleState();
}

class _UserBubbleState extends State<_UserBubble> {
  bool _editing = false;
  late final TextEditingController _ctrl;

  @override
  void initState() {
    super.initState();
    _ctrl = TextEditingController();
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  void _startEditing() {
    _ctrl.text = widget.message.content;
    setState(() => _editing = true);
  }

  void _submit() {
    final text = _ctrl.text.trim();
    if (text.isNotEmpty && widget.onEdit != null) {
      setState(() => _editing = false);
      widget.onEdit!(text);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_editing) {
      return Padding(
        padding: const EdgeInsets.only(left: 24, bottom: 16),
        child: Container(
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
          decoration: BoxDecoration(
            color:        AppTheme.surfaceRaised,
            borderRadius: BorderRadius.circular(14),
            border:       Border.all(color: AppTheme.accent.withAlpha(120)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              TextField(
                controller: _ctrl,
                autofocus:  true,
                maxLines:   8,
                minLines:   2,
                style: const TextStyle(
                    color: AppTheme.textPrimary, fontSize: 15),
                decoration: InputDecoration(
                  filled:    true,
                  fillColor: AppTheme.surface,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(10),
                    borderSide: const BorderSide(color: AppTheme.border),
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(10),
                    borderSide: const BorderSide(color: AppTheme.accent),
                  ),
                  contentPadding: const EdgeInsets.all(12),
                  isDense: true,
                ),
                onSubmitted: (_) => _submit(),
              ),
              const SizedBox(height: 8),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextButton(
                    onPressed: () => setState(() => _editing = false),
                    style: TextButton.styleFrom(
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        minimumSize: const Size(0, 32)),
                    child: const Text('Cancel',
                        style: TextStyle(
                            color: AppTheme.textSecondary, fontSize: 13)),
                  ),
                  const SizedBox(width: 8),
                  ElevatedButton(
                    onPressed: _submit,
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
                    child: const Text('Send',
                        style: TextStyle(
                            fontSize: 13, fontWeight: FontWeight.w600)),
                  ),
                ],
              ),
            ],
          ),
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.only(left: 48, bottom: 16),
      child: Align(
        alignment: Alignment.centerRight,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            GestureDetector(
              onLongPress: () => _showMessageMenu(
                context,
                message:         widget.message,
                onEditRequested: widget.onEdit != null ? _startEditing : null,
                onToggleStar:    widget.onToggleStar,
                onFork:          widget.onFork,
              ),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                decoration: BoxDecoration(
                  color: AppTheme.userBubble,
                  borderRadius: const BorderRadius.only(
                    topLeft:     Radius.circular(18),
                    topRight:    Radius.circular(4),
                    bottomLeft:  Radius.circular(18),
                    bottomRight: Radius.circular(18),
                  ),
                  border: Border.all(
                      color: AppTheme.accentDim.withOpacity(0.4)),
                ),
                child: Text(
                  widget.message.content,
                  style: const TextStyle(
                    color:    AppTheme.textPrimary,
                    fontSize: 15,
                    height:   1.5,
                  ),
                ),
              ),
            ),
            if (widget.message.starred)
              const Padding(
                padding: EdgeInsets.only(top: 4, right: 4),
                child: Icon(Icons.star_rounded,
                    size: 12, color: Colors.amber),
              ),
          ],
        ),
      ),
    );
  }
}

// ── Assistant bubble ─────────────────────────────────────────────────────────

class _AssistantBubble extends StatelessWidget {
  final Message message;
  final VoidCallback? onRegenerate;
  final VoidCallback? onToggleStar;

  const _AssistantBubble({
    required this.message,
    this.onRegenerate,
    this.onToggleStar,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 24, bottom: 16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Avatar
          Container(
            width:  32,
            height: 32,
            margin: const EdgeInsets.only(right: 10, top: 2),
            decoration: BoxDecoration(
              color:        AppTheme.accentDim,
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Icon(Icons.memory_rounded,
                size: 16, color: AppTheme.accent),
          ),

          // Bubble + actions
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                GestureDetector(
                  onLongPress: () => _showMessageMenu(
                    context,
                    message:      message,
                    onToggleStar: onToggleStar,
                  ),
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 16, vertical: 12),
                    decoration: BoxDecoration(
                      color: AppTheme.aiBubble,
                      borderRadius: const BorderRadius.only(
                        topLeft:     Radius.circular(4),
                        topRight:    Radius.circular(18),
                        bottomLeft:  Radius.circular(18),
                        bottomRight: Radius.circular(18),
                      ),
                      border: Border.all(color: AppTheme.border),
                    ),
                    child: message.content.isEmpty && message.isStreaming
                        ? _ThinkingIndicator()
                        : MarkdownBody(
                            data:          message.content,
                            selectable:    true,
                            styleSheet:    _markdownStyle(),
                            softLineBreak: true,
                          ),
                  ),
                ),

                if (message.isError)
                  const Padding(
                    padding: EdgeInsets.only(top: 4, left: 4),
                    child: Text(
                      'Generation failed',
                      style: TextStyle(color: AppTheme.error, fontSize: 11),
                    ),
                  ),

                // Star indicator + action row
                if (message.status == MessageStatus.done &&
                    message.content.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 6, left: 2),
                    child: Row(
                      children: [
                        if (message.starred)
                          const Icon(Icons.star_rounded,
                              size: 13, color: Colors.amber),
                        const Spacer(),
                        // Regenerate button (only on last message)
                        if (onRegenerate != null)
                          _ActionButton(
                            icon:    Icons.refresh_rounded,
                            tooltip: 'Regenerate',
                            onTap:   onRegenerate!,
                          ),
                        _CopyButton(text: message.content),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  MarkdownStyleSheet _markdownStyle() {
    return MarkdownStyleSheet(
      p:    const TextStyle(color: AppTheme.textPrimary,   fontSize: 15, height: 1.6),
      h1:   const TextStyle(color: AppTheme.textPrimary,   fontSize: 20, fontWeight: FontWeight.w700),
      h2:   const TextStyle(color: AppTheme.textPrimary,   fontSize: 17, fontWeight: FontWeight.w600),
      h3:   const TextStyle(color: AppTheme.textPrimary,   fontSize: 15, fontWeight: FontWeight.w600),
      code: const TextStyle(color: AppTheme.accent,        fontSize: 13, fontFamily: 'monospace'),
      codeblockDecoration: BoxDecoration(
        color:        AppTheme.surfaceRaised,
        borderRadius: BorderRadius.circular(8),
        border:       Border.all(color: AppTheme.border),
      ),
      blockquoteDecoration: BoxDecoration(
        border: Border(left: BorderSide(color: AppTheme.accent, width: 3)),
      ),
      blockquotePadding: const EdgeInsets.only(left: 12),
      strong: const TextStyle(color: AppTheme.textPrimary,   fontWeight: FontWeight.w600),
      em:     const TextStyle(color: AppTheme.textSecondary, fontStyle: FontStyle.italic),
      a:      const TextStyle(color: AppTheme.accent,        decoration: TextDecoration.underline),
    );
  }
}

// ── Thinking indicator ───────────────────────────────────────────────────────

class _ThinkingIndicator extends StatefulWidget {
  @override
  State<_ThinkingIndicator> createState() => _ThinkingIndicatorState();
}

class _ThinkingIndicatorState extends State<_ThinkingIndicator>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;
  late final Animation<double>   _anim;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
        vsync: this, duration: const Duration(milliseconds: 900))
      ..repeat(reverse: true);
    _anim = CurvedAnimation(parent: _ctrl, curve: Curves.easeInOut);
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FadeTransition(
      opacity: _anim,
      child: const Text(
        '●●●',
        style: TextStyle(
            color: AppTheme.textMuted, fontSize: 14, letterSpacing: 4),
      ),
    );
  }
}

// ── Small action button ───────────────────────────────────────────────────────

class _ActionButton extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;

  const _ActionButton({
    required this.icon,
    required this.tooltip,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return IconButton(
      onPressed: onTap,
      iconSize:  16,
      tooltip:   tooltip,
      padding:   EdgeInsets.zero,
      constraints: const BoxConstraints(minWidth: 28, minHeight: 28),
      icon: Icon(icon, color: AppTheme.textMuted, size: 16),
    );
  }
}

// ── Copy button ──────────────────────────────────────────────────────────────

class _CopyButton extends StatefulWidget {
  final String text;
  const _CopyButton({required this.text});

  @override
  State<_CopyButton> createState() => _CopyButtonState();
}

class _CopyButtonState extends State<_CopyButton> {
  bool _copied = false;

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: widget.text));
    setState(() => _copied = true);
    await Future.delayed(const Duration(seconds: 2));
    if (mounted) setState(() => _copied = false);
  }

  @override
  Widget build(BuildContext context) {
    return IconButton(
      onPressed: _copy,
      iconSize:  16,
      tooltip:   _copied ? 'Copied!' : 'Copy',
      padding:   EdgeInsets.zero,
      constraints: const BoxConstraints(minWidth: 28, minHeight: 28),
      icon: Icon(
        _copied ? Icons.check_rounded : Icons.copy_rounded,
        color: _copied ? AppTheme.success : AppTheme.textMuted,
        size:  16,
      ),
    );
  }
}

// ── Summary card ──────────────────────────────────────────────────────────────

class _SummaryCard extends StatefulWidget {
  final Message message;
  const _SummaryCard({required this.message});

  @override
  State<_SummaryCard> createState() => _SummaryCardState();
}

class _SummaryCardState extends State<_SummaryCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: GestureDetector(
        onTap: () => setState(() => _expanded = !_expanded),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: AppTheme.surfaceRaised,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: AppTheme.accent.withAlpha(80)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.compress_rounded,
                      size: 13, color: AppTheme.accent),
                  const SizedBox(width: 6),
                  const Text(
                    'Context compressed',
                    style: TextStyle(
                      color:      AppTheme.accent,
                      fontSize:   12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const Spacer(),
                  Icon(
                    _expanded
                        ? Icons.expand_less_rounded
                        : Icons.expand_more_rounded,
                    size: 14,
                    color: AppTheme.textMuted,
                  ),
                ],
              ),
              if (_expanded) ...[
                const SizedBox(height: 8),
                Text(
                  widget.message.content,
                  style: const TextStyle(
                    color:    AppTheme.textSecondary,
                    fontSize: 12,
                    height:   1.5,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
