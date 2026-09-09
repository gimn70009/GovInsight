import { Navigate, Route, Routes } from 'react-router-dom'
import { authStore } from './api/client'
import AppShell from './components/AppShell'
import DocumentsPage from './pages/DocumentsPage'
import LoginPage from './pages/LoginPage'
import MonitoringPage from './pages/MonitoringPage'
import TelegramPage from './pages/TelegramPage'

function ProtectedLayout() {
  return authStore.get() ? <AppShell /> : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<ProtectedLayout />}>
        <Route path="/monitoring" element={<MonitoringPage />} />
        <Route path="/documents" element={<DocumentsPage />} />
        <Route path="/telegram" element={<TelegramPage />} />
      </Route>
      <Route path="*" element={<Navigate to={authStore.get() ? '/monitoring' : '/login'} replace />} />
    </Routes>
  )
}
