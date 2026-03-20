import 'package:flutter/material.dart';
import '../../theme/app_theme.dart';

class ChatInputBar extends StatefulWidget {
  final bool isGenerating;
  final void Function(String) onSend;
  final VoidCallback onStop;

  const ChatInputBar({
    super.key,
    required this.isGenerating,
    required this.onSend,
    required this.onStop,
  });

  @override
  State<ChatInputBar> createState() => _ChatInputBarState();
}

class _ChatInputBarState extends State<ChatInputBar> {
  final _controller  = TextEditingController();
  final _focusNode   = FocusNode();
  bool  _hasText     = false;

  @override
  void initState() {
    super.initState();
    _controller.addListener(() {
      final hasText = _controller.text.trim().isNotEmpty;
      if (hasText != _hasText) setState(() => _hasText = hasText);
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _submit() {
    final text = _controller.text.trim();
    if (text.isEmpty || widget.isGenerating) return;
    widget.onSend(text);
    _controller.clear();
    _focusNode.requestFocus();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: EdgeInsets.fromLTRB(
        12, 10, 12,
        10 + MediaQuery.of(context).viewPadding.bottom,
      ),
      decoration: const BoxDecoration(
        color: AppTheme.background,
        border: Border(top: BorderSide(color: AppTheme.border)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          // Text field
          Expanded(
            child: TextField(
              controller:   _controller,
              focusNode:    _focusNode,
              enabled:      !widget.isGenerating,
              maxLines:     6,
              minLines:     1,
              textInputAction: TextInputAction.newline,
              keyboardType:    TextInputType.multiline,
              style: const TextStyle(
                color:    AppTheme.textPrimary,
                fontSize: 15,
                height:   1.5,
              ),
              decoration: const InputDecoration(
                hintText:       'Ask anything…',
                hintStyle:      TextStyle(color: AppTheme.textMuted),
                border:         InputBorder.none,
                enabledBorder:  InputBorder.none,
                focusedBorder:  InputBorder.none,
                filled:         true,
                fillColor:      AppTheme.surfaceRaised,
                contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              ),
            ),
          ),

          const SizedBox(width: 8),

          // Send / Stop button
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 180),
            child: widget.isGenerating
                ? _StopButton(onStop: widget.onStop)
                : _SendButton(
                    enabled: _hasText,
                    onSend:  _submit,
                  ),
          ),
        ],
      ),
    );
  }
}

class _SendButton extends StatelessWidget {
  final bool enabled;
  final VoidCallback onSend;
  const _SendButton({required this.enabled, required this.onSend});

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 180),
      width:  44,
      height: 44,
      decoration: BoxDecoration(
        color:        enabled ? AppTheme.accent : AppTheme.surfaceRaised,
        borderRadius: BorderRadius.circular(12),
        border:       Border.all(color: enabled ? AppTheme.accent : AppTheme.border),
      ),
      child: IconButton(
        onPressed: enabled ? onSend : null,
        icon: const Icon(Icons.arrow_upward_rounded),
        iconSize: 20,
        color: enabled ? Colors.white : AppTheme.textMuted,
        padding: EdgeInsets.zero,
      ),
    );
  }
}

class _StopButton extends StatelessWidget {
  final VoidCallback onStop;
  const _StopButton({required this.onStop});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width:  44,
      height: 44,
      child: Container(
        decoration: BoxDecoration(
          color:        AppTheme.surfaceRaised,
          borderRadius: BorderRadius.circular(12),
          border:       Border.all(color: AppTheme.border),
        ),
        child: IconButton(
          onPressed: onStop,
          icon: const Icon(Icons.stop_rounded),
          iconSize: 20,
          color: AppTheme.error,
          padding: EdgeInsets.zero,
        ),
      ),
    );
  }
}
