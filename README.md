# Volume Screen Lock

App Android que apaga e acende a tela com **Volume+ e Volume− pressionados juntos**, e protege a tela de bloqueio contra toques fantasmas. Feito e ajustado para o **Samsung Galaxy S24 FE** (One UI), mas funciona em outros aparelhos com Android 10 ou superior.

## O que ele faz

| Situação | Ação | Resultado |
|---|---|---|
| Tela acesa | Volume+ e Volume− juntos | Apaga e bloqueia a tela, igual ao botão lateral |
| Tela apagada | Volume+ e Volume− juntos | Acende a tela na tela de bloqueio |
| Tela de bloqueio | Qualquer toque | Ignorado, exceto na área da digital (escudo) |
| Tela de bloqueio | Volume+ sozinho | Libera o toque (para digitar PIN) até a tela apagar |
| Desbloqueado pela digital | — | Escudo sai e o toque volta ao normal |

Cliques simples de volume continuam mudando o volume normalmente. Durante ligações, os botões funcionam como sempre, e chamadas e alarmes liberam o toque sozinhos.

## Como gerar o APK pelo GitHub

1. Crie um repositório novo no GitHub (pode ser privado).
2. Envie todos os arquivos deste projeto, **incluindo a pasta oculta `.github`**.
3. O GitHub Actions compila sozinho em alguns minutos (aba **Actions**).
4. O APK aparece na aba **Releases** (`TelaVolume-v1.0.X.apk`).

Para gerar de novo sem alterar nada: aba **Actions › Gerar APK › Run workflow**.

## Instalação no S24 FE

1. Baixe o APK pelo celular e abra. Permita "Instalar apps desconhecidos" para o navegador, se for pedido.
2. Abra o **Tela Volume** e siga os passos da tela:
   1. **Acessibilidade:** Configurações › Acessibilidade › Apps instalados › Tela Volume › ativar. Se aparecer *Configuração restrita*: Configurações › Aplicativos › Tela Volume › ⋮ › **Permitir configurações restritas**, depois volte e ative.
   2. **Bateria:** Configurações › Aplicativos › Tela Volume › Bateria › **Irrestrito**.
   3. **Sensor em repouso** (desligar): *Always On Display*, *Impressão digital sempre ativa* e *Toque duplo para ligar a tela*.
   4. **Calibrar a digital:** arraste o quadrado até o local do ícone da digital e use *Visualizar* para conferir.
3. Use **Apagar a tela agora** e teste o acendimento pelo volume. A tela mostra o último acendimento registrado e por qual método funcionou.

## Como funciona por dentro

- **Apagar:** serviço de acessibilidade intercepta os botões de volume e usa a ação de sistema "Bloquear tela", que mantém a digital funcionando (ao contrário do bloqueio por administrador, que exige PIN).
- **Acender:** com a tela apagada, a One UI só repassa os botões de volume para quem está tocando mídia. O app abre uma sessão de mídia com controle de volume próprio e toca silêncio enquanto a tela está apagada. Se o sistema mudar o volume em vez de avisar a sessão, o app detecta a mudança (plano B) e restaura o volume depois.
- **Escudo:** quatro camadas invisíveis cobrem a tela ao redor do sensor. O quadrado do sensor fica sem camada, então só ali o toque chega ao sistema. O escudo é colocado já com a tela apagada, para não haver brecha no instante em que ela acende.

## Limitações

- Nenhum app consegue desligar o sensor de toque sem root. O escudo impede que toques fantasmas façam algo na tela de bloqueio, mas não corrige o defeito.
- Com o celular desbloqueado, o toque fica normal (por escolha de projeto). Use Volume+ e Volume− para apagar rapidamente quando necessário.
- Se outro app estiver tocando música com a tela apagada, os botões de volume ficam com o app de música quando a tela foi apagada pelo botão lateral.
- Cada build usa uma chave de assinatura nova. Para atualizar, **desinstale a versão anterior** antes de instalar a nova (as configurações serão perdidas).
- Segurar Volume+ e Volume− por 3 segundos aciona o atalho de acessibilidade do Android. O clique deve ser rápido.
