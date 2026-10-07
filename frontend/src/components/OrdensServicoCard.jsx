import React from 'react'
import { Calendar, MapPin, ChevronDown, AlertCircle, CheckCircle, Clock } from 'lucide-react'
import { formatarDataCivil } from '../utils/datas.js'

const statusIconMap = {
  ABERTA: <AlertCircle className="w-4 h-4 text-blue-600" />,
  AGUARDANDO_VISTORIA: <Clock className="w-4 h-4 text-blue-600" />,
  AGUARDANDO_RETIRADA: <Clock className="w-4 h-4 text-cyan-600" />,
  EM_EXECUCAO: <Clock className="w-4 h-4 text-yellow-600" />,
  AGUARDANDO_VALIDACAO: <AlertCircle className="w-4 h-4 text-purple-600" />,
  AGUARDANDO_DEVOLUCAO: <Clock className="w-4 h-4 text-orange-600" />,
  AGUARDANDO_AUDITORIA: <Clock className="w-4 h-4 text-violet-600" />,
  AGUARDANDO_ENCERRAMENTO: <Clock className="w-4 h-4 text-teal-600" />,
  CONCLUIDA: <CheckCircle className="w-4 h-4 text-green-600" />,
  FATURADA: <CheckCircle className="w-4 h-4 text-gray-600" />,
}

const statusLabelMap = {
  ABERTA: 'Aberta',
  AGUARDANDO_VISTORIA: 'Aguardando vistoria',
  AGUARDANDO_RETIRADA: 'Aguardando retirada',
  EM_EXECUCAO: 'Em Execução',
  AGUARDANDO_VALIDACAO: 'Aguardando',
  AGUARDANDO_DEVOLUCAO: 'Aguardando devolução',
  AGUARDANDO_AUDITORIA: 'Aguardando auditoria',
  AGUARDANDO_ENCERRAMENTO: 'Aguardando encerramento',
  CONCLUIDA: 'Concluída',
  FATURADA: 'Faturada',
}

export default function OrdensServicoCard({ ordem, onAtualizarStatus }) {
  return (
    <div className="bg-white border border-gray-200 rounded-lg p-4 shadow-sm hover:shadow-md transition cursor-pointer">
      {/* Cabeçalho Card */}
      <div className="flex items-start justify-between mb-3">
        <div className="flex-1">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider">
            {ordem.simulacao ? 'TESTE · ' : ''}{ordem.numeroOs || 'S/N'}
          </p>
          <p className="text-sm font-bold text-gray-900 mt-1">
            {ordem.contrato?.cliente || 'Cliente não definido'}
          </p>
        </div>
        <div className="flex items-center gap-1 px-2 py-1 bg-gray-100 rounded text-xs font-medium text-gray-700">
          {statusIconMap[ordem.status]}
          <span>{statusLabelMap[ordem.status]}</span>
        </div>
      </div>

      {/* Detalhes */}
      <div className="space-y-2 mb-4 text-xs text-gray-600">
        {/* Projeto */}
        {ordem.projeto && (
          <div>
            <p className="text-gray-500 font-medium">Projeto</p>
            <p className="text-gray-900 font-medium">{ordem.projeto.id}</p>
          </div>
        )}

        {/* Data de Execução */}
        {ordem.dataExecucao && (
          <div className="flex items-center gap-2 text-gray-600">
            <Calendar className="w-4 h-4" />
            <span>{formatarDataCivil(ordem.dataExecucao)}</span>
          </div>
        )}

        {/* Descrição Curta */}
        {ordem.descricao && (
          <p className="text-gray-600 line-clamp-2 text-xs italic">
            "{ordem.descricao.substring(0, 60)}..."
          </p>
        )}
      </div>

      {/* Botão de Ação */}
      <button
        onClick={onAtualizarStatus}
        className="w-full flex items-center justify-center gap-2 px-3 py-2 bg-blue-50 text-blue-600 rounded font-medium text-sm hover:bg-blue-100 transition"
      >
        <ChevronDown className="w-4 h-4" />
        Atualizar Status
      </button>
    </div>
  )
}
