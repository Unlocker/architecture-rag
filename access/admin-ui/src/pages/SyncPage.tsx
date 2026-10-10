import { useState } from 'react';
import { AuditTab } from './sync/AuditTab';
import { DlqTab } from './sync/DlqTab';
import { EventsTab } from './sync/EventsTab';
import { IdentityTab } from './sync/IdentityTab';
import { SourcesTab } from './sync/SourcesTab';

const TABS = [
  { id: 'sources', title: 'Источники', view: SourcesTab },
  { id: 'events', title: 'Журнал событий', view: EventsTab },
  { id: 'dlq', title: 'DLQ', view: DlqTab },
  { id: 'identity', title: 'Identity', view: IdentityTab },
  { id: 'audit', title: 'Аудит', view: AuditTab },
] as const;

/** Раздел «Синхронизация»: вкладки состояния источников, журнала, DLQ, identity и аудита. */
export function SyncPage() {
  const [tab, setTab] = useState<(typeof TABS)[number]['id']>('sources');
  const View = TABS.find((t) => t.id === tab)!.view;
  return (
    <section>
      <h2>Синхронизация</h2>
      <div role="tablist" aria-label="Синхронизация">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={t.id === tab}
            onClick={() => setTab(t.id)}
          >
            {t.title}
          </button>
        ))}
      </div>
      <View />
    </section>
  );
}
