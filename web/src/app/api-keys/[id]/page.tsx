import { AppShell } from "@/components/app-shell";
import { ApiKeyDetailPage } from "@/components/api-key-detail";

export default async function ApiKeyPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <AppShell><ApiKeyDetailPage id={id} /></AppShell>;
}
