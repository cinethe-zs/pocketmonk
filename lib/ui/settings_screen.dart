import 'dart:io';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/model_manager.dart';
import '../theme/app_theme.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Settings'),
        leading: IconButton(
          icon: const Icon(Icons.close_rounded),
          onPressed: () => Navigator.pop(context),
        ),
      ),
      body: Consumer<ModelManager>(
        builder: (context, mgr, _) => ListView(
          padding: const EdgeInsets.all(20),
          children: [
            _sectionLabel('Active model'),
            const SizedBox(height: 10),
            _ActiveModelCard(mgr: mgr),
            const SizedBox(height: 28),
            _sectionLabel('Downloaded models'),
            const SizedBox(height: 10),
            _DownloadedModelsList(mgr: mgr),
            const SizedBox(height: 28),
            _sectionLabel('Download more models'),
            const SizedBox(height: 10),
            _CatalogueList(mgr: mgr),
            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }

  Widget _sectionLabel(String text) => Text(
    text.toUpperCase(),
    style: const TextStyle(
      color:       AppTheme.textMuted,
      fontSize:    11,
      fontWeight:  FontWeight.w700,
      letterSpacing: 0.8,
    ),
  );
}

// ── Active model card ─────────────────────────────────────────────────────────

class _ActiveModelCard extends StatelessWidget {
  final ModelManager mgr;
  const _ActiveModelCard({required this.mgr});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppTheme.accentDim,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppTheme.accent.withAlpha(80)),
      ),
      child: Row(
        children: [
          const Icon(Icons.self_improvement_rounded,
              color: AppTheme.accent, size: 22),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              mgr.activeModelPath.isEmpty
                  ? 'No model selected'
                  : mgr.activeModelName,
              style: const TextStyle(
                color:      AppTheme.textPrimary,
                fontSize:   14,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Downloaded models list ────────────────────────────────────────────────────

class _DownloadedModelsList extends StatelessWidget {
  final ModelManager mgr;
  const _DownloadedModelsList({required this.mgr});

  @override
  Widget build(BuildContext context) {
    if (mgr.downloadedPaths.isEmpty) {
      return Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: AppTheme.surfaceRaised,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: AppTheme.border),
        ),
        child: const Text('No models downloaded yet.',
            style: TextStyle(color: AppTheme.textMuted, fontSize: 13)),
      );
    }

    return Column(
      children: mgr.downloadedPaths.map((path) {
        final filename = path.split('/').last;
        final entry = modelCatalogue
            .where((e) => e.filename == filename)
            .firstOrNull;
        final name     = entry?.name ?? filename;
        final isActive = path == mgr.activeModelPath;

        return Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: _ModelTile(
            name:     name,
            filename: filename,
            isActive: isActive,
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (!isActive)
                  IconButton(
                    icon: const Icon(Icons.radio_button_unchecked_rounded,
                        color: AppTheme.textMuted, size: 20),
                    tooltip: 'Use this model',
                    onPressed: () => _selectModel(context, mgr, path),
                  )
                else
                  const Icon(Icons.radio_button_checked_rounded,
                      color: AppTheme.accent, size: 20),
                IconButton(
                  icon: const Icon(Icons.delete_outline_rounded,
                      color: AppTheme.textMuted, size: 20),
                  tooltip: 'Delete',
                  onPressed: () => _confirmDelete(context, mgr, path, name),
                ),
              ],
            ),
          ),
        );
      }).toList(),
    );
  }

  Future<void> _selectModel(
      BuildContext context, ModelManager mgr, String path) async {
    final chat = context.findAncestorStateOfType<State>(); // unused
    await mgr.setActiveModel(path);
    if (!context.mounted) return;
    // Signal to root to reinitialise the service.
    Navigator.pop(context, path);
  }

  Future<void> _confirmDelete(
      BuildContext context, ModelManager mgr, String path, String name) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: AppTheme.surface,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: const Text('Delete model?',
            style: TextStyle(color: AppTheme.textPrimary)),
        content: Text('$name will be removed from your device.',
            style: const TextStyle(color: AppTheme.textSecondary)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel',
                style: TextStyle(color: AppTheme.textSecondary)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Delete',
                style: TextStyle(color: AppTheme.error)),
          ),
        ],
      ),
    );
    if (confirmed == true) await mgr.deleteModel(path);
  }
}

// ── Catalogue (not yet downloaded) ───────────────────────────────────────────

class _CatalogueList extends StatelessWidget {
  final ModelManager mgr;
  const _CatalogueList({required this.mgr});

  @override
  Widget build(BuildContext context) {
    // Only show models that are not already fully downloaded.
    final notDownloaded = modelCatalogue
        .where((e) => !mgr.downloadedPaths.any((p) => p.endsWith(e.filename)))
        .toList();

    // Also show models currently being downloaded.
    final downloading = modelCatalogue
        .where((e) =>
            mgr.downloadStatus(e.filename) == DownloadStatus.downloading ||
            mgr.downloadStatus(e.filename) == DownloadStatus.error)
        .where((e) => mgr.downloadedPaths.every((p) => !p.endsWith(e.filename)))
        .toList();

    final toShow = {
      ...notDownloaded,
      ...downloading,
    }.toList();

    if (toShow.isEmpty) {
      return Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: AppTheme.surfaceRaised,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: AppTheme.border),
        ),
        child: const Text('All catalogue models are downloaded.',
            style: TextStyle(color: AppTheme.textMuted, fontSize: 13)),
      );
    }

    return Column(
      children: toShow.map((entry) {
        final status   = mgr.downloadStatus(entry.filename);
        final progress = mgr.downloadProgress(entry.filename);
        final error    = mgr.downloadError(entry.filename);

        return Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: AppTheme.surfaceRaised,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(
                color: status == DownloadStatus.error
                    ? AppTheme.error.withAlpha(120)
                    : AppTheme.border,
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(entry.name,
                              style: const TextStyle(
                                color:      AppTheme.textPrimary,
                                fontSize:   14,
                                fontWeight: FontWeight.w600,
                              )),
                          const SizedBox(height: 2),
                          Text(entry.subtitle,
                              style: const TextStyle(
                                  color: AppTheme.textMuted, fontSize: 12)),
                        ],
                      ),
                    ),
                    Text(entry.size,
                        style: const TextStyle(
                            color: AppTheme.textSecondary,
                            fontSize: 12,
                            fontWeight: FontWeight.w500)),
                  ],
                ),
                const SizedBox(height: 12),
                if (status == DownloadStatus.idle) ...[
                  SizedBox(
                    width: double.infinity,
                    height: 40,
                    child: ElevatedButton.icon(
                      onPressed: () => mgr.startDownload(entry),
                      icon:  const Icon(Icons.download_rounded, size: 16),
                      label: const Text('Download',
                          style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: AppTheme.accent,
                        foregroundColor: Colors.white,
                        shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(10)),
                        elevation: 0,
                      ),
                    ),
                  ),
                ] else if (status == DownloadStatus.downloading) ...[
                  ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: LinearProgressIndicator(
                      value: progress > 0 ? progress : null,
                      backgroundColor: AppTheme.border,
                      valueColor: const AlwaysStoppedAnimation<Color>(AppTheme.accent),
                      minHeight: 6,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        progress > 0
                            ? '${(progress * 100).toStringAsFixed(1)}%'
                            : 'Connecting…',
                        style: const TextStyle(
                            color: AppTheme.textMuted, fontSize: 12),
                      ),
                      GestureDetector(
                        onTap: () => mgr.cancelDownload(entry.filename),
                        child: const Text('Cancel',
                            style: TextStyle(
                                color: AppTheme.error,
                                fontSize: 12,
                                fontWeight: FontWeight.w500)),
                      ),
                    ],
                  ),
                ] else if (status == DownloadStatus.error) ...[
                  Text(error,
                      style: const TextStyle(
                          color: AppTheme.error, fontSize: 11),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis),
                  const SizedBox(height: 8),
                  SizedBox(
                    width: double.infinity,
                    height: 36,
                    child: OutlinedButton.icon(
                      onPressed: () => mgr.startDownload(entry),
                      icon:  const Icon(Icons.refresh_rounded, size: 14),
                      label: const Text('Retry', style: TextStyle(fontSize: 12)),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: AppTheme.textSecondary,
                        side: const BorderSide(color: AppTheme.border),
                        shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(8)),
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
        );
      }).toList(),
    );
  }
}

// ── Shared tile widget ────────────────────────────────────────────────────────

class _ModelTile extends StatelessWidget {
  final String  name;
  final String  filename;
  final bool    isActive;
  final Widget  trailing;

  const _ModelTile({
    required this.name,
    required this.filename,
    required this.isActive,
    required this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: isActive ? AppTheme.accentDim : AppTheme.surfaceRaised,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: isActive ? AppTheme.accent.withAlpha(120) : AppTheme.border,
          width: isActive ? 1.5 : 1,
        ),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(name,
                    style: const TextStyle(
                      color:      AppTheme.textPrimary,
                      fontSize:   14,
                      fontWeight: FontWeight.w600,
                    )),
                const SizedBox(height: 2),
                Text(filename,
                    style: const TextStyle(
                        color:    AppTheme.textMuted,
                        fontSize: 11,
                        fontFamily: 'monospace'),
                    overflow: TextOverflow.ellipsis),
              ],
            ),
          ),
          trailing,
        ],
      ),
    );
  }
}
