import { AccountDetails } from "@/components/account-details";
import { AppShell } from "@/components/app-shell";

export default async function AccountDetailsPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <AppShell><AccountDetails accountId={id} /></AppShell>;
}
