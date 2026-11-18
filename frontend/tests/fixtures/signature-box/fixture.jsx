import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import SignatureBox from '../../../src/components/SignatureBox.jsx';
import '../../../src/index.css';

const papeis = ['Conferente', 'Retirante'];

function Campos() {
  const [visivel, mostrar] = useState(true);
  const [largura, ajustar] = useState(304);
  const [valores, atualizar] = useState(['', '']);
  window.ensaio = {
    valores,
    fechar: () => mostrar(false),
    reabrir: iniciais => { atualizar(iniciais); mostrar(true); },
    receber: (papel, valor) => atualizar(atuais => atuais.map(
      (item, posicao) => posicao === papel ? valor : item,
    )),
    largura: ajustar,
  };
  return visivel && papeis.map((papel, i) => (
    <section key={papel} aria-label={papel} style={{ width: largura }}>
      <SignatureBox label={papel} value={valores[i]} simulacao onChange={valor => {
        atualizar(atuais => atuais.map((item, posicao) => posicao === i ? valor : item));
      }} />
    </section>
  ));
}

createRoot(document.getElementById('root')).render(<Campos />);
