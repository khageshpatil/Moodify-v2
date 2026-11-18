import { Toaster } from "@/components/ui/toaster";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import AuraApp from "./aura/AuraApp";

const App = () => (
  <>
    <Toaster />
    <BrowserRouter
      basename={import.meta.env.BASE_URL}
      future={{
        v7_startTransition: true,
        v7_relativeSplatPath: true,
      }}
    >
      <Routes>
        <Route path="/*" element={<AuraApp />} />
      </Routes>
    </BrowserRouter>
  </>
);

export default App;
