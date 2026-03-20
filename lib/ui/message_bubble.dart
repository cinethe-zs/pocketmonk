import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown/flutter_markdown.dart';

import '../models/message.dart';
import '../theme/app_theme.dart';

class MessageBubble extends StatelessWidget {
  final Message message;

  const MessageBubble({super.key, required this.message});

  @override
  Widget build(BuildContext context) {
    return message.isUser
        ? _UserBubble(message: message)
        : _AssistantBubble(message: message);
  }
}

// ── User bubble ──────────────────────────────────────────────────────────────

class _UserBubble extends StatelessWidget {
  final Message message;
  const _UserBubble({required this.message});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(left: 48, bottom: 16),
      child: Align(
        alignment: Alignment.centerRight,
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
            border: Border.all(color: AppTheme.accentDim.withOpacity(0.4)),
          ),
          child: Text(
            message.content,
            style: const TextStyle(
              color:    AppTheme.textPrimary,
              fontSize: 15,
              height:   1.5,
            ),
          ),
        ),
      ),
    );
  }
}

// ── Assistant bubble ─────────────────────────────────────────────────────────

class _AssistantBubble extends StatelessWidget {
  final Message message;
  const _AssistantBubble({required this.message});

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
            child: const Icon(Icons.memory_rounded, size: 16, color: AppTheme.accent),
          ),

          // Bubble
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
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
                if (message.isError)
                  Padding(
                    padding: const EdgeInsets.only(top: 4, left: 4),
                    child: const Text(
                      'Generation failed',
                      style: TextStyle(color: AppTheme.error, fontSize: 11),
                    ),
                  ),
              ],
            ),
          ),

          // Copy button
          if (message.status == MessageStatus.done && message.content.isNotEmpty)
            _CopyButton(text: message.content),
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
        style: TextStyle(color: AppTheme.textMuted, fontSize: 14, letterSpacing: 4),
      ),
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
      icon: Icon(
        _copied ? Icons.check_rounded : Icons.copy_rounded,
        color: _copied ? AppTheme.success : AppTheme.textMuted,
        size:  16,
      ),
    );
  }
}
