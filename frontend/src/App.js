import { QueryClient, QueryClientProvider } from 'react-query';
import GlobalStyle from './GlobalStyle';
import Router from './Router';
import { useRecoilState } from 'recoil';
import { useEffect } from 'react';
import { loginState, tokenState, baseUrl } from './Atoms';
import axios from 'axios';

const queryClient = new QueryClient();

function App() {
  const [, setLoginStatus] = useRecoilState(loginState);
  const [, setToken] = useRecoilState(tokenState);
  const [BASEURL,] = useRecoilState(baseUrl);

  useEffect(() => {
    const jwtToken = localStorage.getItem('accessToken');
    if (jwtToken) {
      setToken(jwtToken);
      setLoginStatus(true);
      axios.defaults.headers.common['Authorization'] = `Bearer ${jwtToken}`;
      axios.defaults.baseURL = BASEURL;
      axios.defaults.withCredentials = true;
    }
  }, []);

  return (
    <QueryClientProvider client={queryClient}>
      <GlobalStyle/>
      <Router/>
    </QueryClientProvider>
  );
}

export default App;
