import { createFileRoute } from "@tanstack/react-router";
import { RecoverApp } from "@/components/recover-app";

export const Route = createFileRoute("/")({ component: Home });

function Home() {
  return <RecoverApp />;
}
